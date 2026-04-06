package io.tradeflow.payment.service;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PathParam;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * Stripe API reactive client.
 *
 * All calls return Uni<StripeResult> — non-blocking.
 * Quarkus REST Client Reactive uses the Vert.x HTTP client underneath:
 * the calling thread is never parked waiting for Stripe's 200-500ms response.
 *
 * Resilience annotations:
 *   @Retry        — network errors only, not card declines (4xx)
 *   @Timeout      — abort after 3 seconds (Stripe usually < 500ms)
 *   @CircuitBreaker — open after 50% failure in 10-call window
 *
 * Stripe amount convention: amounts are in the smallest currency unit.
 *   MXN $37,998.00 → Stripe receives amount=3799800 (centavos)
 *   USD $9.99       → Stripe receives amount=999 (cents)
 */
@ApplicationScoped
@Slf4j
public class StripeClient {

    @ConfigProperty(name = "stripe.secret-key")
    String stripeSecretKey;

    @ConfigProperty(name = "stripe.webhook-secret")
    String stripeWebhookSecret;

    @ConfigProperty(name = "stripe.api-url", defaultValue = "https://api.stripe.com")
    String stripeApiUrl;

    @Inject
    @RestClient
    StripeRestClient stripeRestClient;

    // ─────────────────────────────────────────────────────────────────────────
    // CHARGE
    // ─────────────────────────────────────────────────────────────────────────

    @Retry(maxRetries = 3,
           delay = 100, delayUnit = ChronoUnit.MILLIS,
           retryOn = StripeNetworkException.class)          // retry network, NOT card declines
    @Timeout(value = 3, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10,
                    failureRatio = 0.50,
                    delay = 30, delayUnit = ChronoUnit.SECONDS)
    public Uni<StripeChargeResult> charge(String paymentMethodToken,
                                          BigDecimal amount,
                                          String currency,
                                          String orderId,
                                          String idempotencyKey) {
        // Stripe amounts are in smallest currency unit (centavos for MXN)
        long amountInCentavos = amount.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP).longValue();

        Map<String, Object> params = new HashMap<>();
        params.put("amount", amountInCentavos);
        params.put("currency", currency.toLowerCase());
        params.put("payment_method", paymentMethodToken);
        params.put("confirm", "true");
        params.put("metadata[order_id]", orderId);
        params.put("metadata[idempotency_key]", idempotencyKey);

        log.debug("Stripe charge: orderId={}, amount={} {}", orderId, amount, currency);

        return stripeRestClient.createCharge(
                        "Bearer " + stripeSecretKey,
                        idempotencyKey,
                        params)
                .map(response -> parseChargeResult(response, amount, currency, orderId))
                .onFailure(ex -> isDeclineError(ex)).recoverWithItem(ex -> {
                    log.warn("Stripe decline: orderId={}, reason={}", orderId, ex.getMessage());
                    return new StripeChargeResult(false, null, amount, currency, orderId,
                            extractDeclineCode(ex), ex.getMessage());
                })
                .onFailure(ex -> isNetworkError(ex)).transform(ex ->
                        (Throwable) Uni.createFrom().failure(
                                new StripeNetworkException("Stripe network error: " + ex.getMessage(), ex)
                        )
                );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // REFUND
    // ─────────────────────────────────────────────────────────────────────────

    @Retry(maxRetries = 2, delay = 200, delayUnit = ChronoUnit.MILLIS,
           retryOn = StripeNetworkException.class)
    @Timeout(value = 3, unit = ChronoUnit.SECONDS)
    public Uni<StripeRefundResult> createRefund(String chargeId,
                                                 BigDecimal amount,
                                                 String reason,
                                                 String idempotencyKey) {
        long amountInCentavos = amount.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP).longValue();

        Map<String, Object> params = Map.of(
                "charge", chargeId,
                "amount", amountInCentavos,
                "reason", mapRefundReason(reason)
        );

        return stripeRestClient.createRefund("Bearer " + stripeSecretKey, idempotencyKey, params)
                .map(response -> parseRefundResult(response, amount));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DISPUTE — submit evidence
    // ─────────────────────────────────────────────────────────────────────────

    @Retry(maxRetries = 2, delay = 200, delayUnit = ChronoUnit.MILLIS,
           retryOn = StripeNetworkException.class)
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    public Uni<Map<String, Object>> updateDisputeEvidence(String stripeDisputeId,
                                                            Map<String, Object> evidence) {
        return stripeRestClient.updateDisputeEvidence(
                "Bearer " + stripeSecretKey,
                stripeDisputeId,
                Map.of("evidence", evidence, "submit", true));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WEBHOOK signature verification
    // Validates HMAC-SHA256 signature from Stripe-Signature header.
    // Must be called with the RAW request body bytes (before any parsing).
    // ─────────────────────────────────────────────────────────────────────────

    public boolean verifyWebhookSignature(String stripeSignatureHeader, byte[] rawBody) {
        try {
            // Parse t=timestamp,v1=signature from header
            String[] parts = stripeSignatureHeader.split(",");
            String timestamp = null;
            String signature = null;
            for (String part : parts) {
                if (part.startsWith("t=")) timestamp = part.substring(2);
                if (part.startsWith("v1=")) signature = part.substring(3);
            }
            if (timestamp == null || signature == null) return false;

            // Replay protection: reject if older than 5 minutes
            long webhookTime = Long.parseLong(timestamp);
            if (Math.abs(System.currentTimeMillis() / 1000 - webhookTime) > 300) {
                log.warn("Stripe webhook timestamp too old: {}", timestamp);
                return false;
            }

            // HMAC-SHA256: signed_payload = timestamp + "." + raw_body
            String signedPayload = timestamp + "." + new String(rawBody);
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    stripeWebhookSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "HmacSHA256"));
            byte[] computedSig = mac.doFinal(
                    signedPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String computedHex = bytesToHex(computedSig);

            return computedHex.equals(signature);
        } catch (Exception e) {
            log.error("Webhook signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private StripeChargeResult parseChargeResult(Map<String, Object> response,
                                                  BigDecimal amount, String currency, String orderId) {
        String status = (String) response.getOrDefault("status", "failed");
        String chargeId = (String) response.get("id");
        boolean succeeded = "succeeded".equals(status);
        return new StripeChargeResult(succeeded, chargeId, amount, currency, orderId,
                null, succeeded ? null : "charge_failed");
    }

    private StripeRefundResult parseRefundResult(Map<String, Object> response, BigDecimal amount) {
        return new StripeRefundResult(
                (String) response.get("id"),
                amount,
                (String) response.getOrDefault("status", "pending"));
    }

    private String mapRefundReason(String reason) {
        return switch (reason) {
            case "BUYER_RETURN"  -> "requested_by_customer";
            case "FRAUDULENT"    -> "fraudulent";
            case "DUPLICATE"     -> "duplicate";
            default              -> "requested_by_customer";
        };
    }

    private boolean isDeclineError(Throwable ex) {
        return ex instanceof StripeCardDeclinedException;
    }

    private boolean isNetworkError(Throwable ex) {
        return ex instanceof java.net.ConnectException
                || ex instanceof java.util.concurrent.TimeoutException;
    }

    private String extractDeclineCode(Throwable ex) {
        if (ex instanceof StripeCardDeclinedException d) return d.getDeclineCode();
        return "card_declined";
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result types
    // ─────────────────────────────────────────────────────────────────────────

    public record StripeChargeResult(
            boolean succeeded,
            String chargeId,
            BigDecimal amount,
            String currency,
            String orderId,
            String declineCode,
            String errorMessage) {}

    public record StripeRefundResult(
            String refundId,
            BigDecimal amount,
            String status) {}
}

// ─────────────────────────────────────────────────────────────────────────────
// Stripe REST Client interface (Quarkus REST Client Reactive)
// Non-blocking HTTP — Vert.x client under the hood
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// Stripe-specific exceptions
// ─────────────────────────────────────────────────────────────────────────────

