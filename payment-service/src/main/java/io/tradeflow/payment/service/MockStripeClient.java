package io.tradeflow.payment.service;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MOCK Stripe Client - NO REAL STRIPE ACCOUNT NEEDED!
 *
 * This is a fake implementation that simulates Stripe responses.
 * Perfect for development, testing, and learning without spending money.
 *
 * To use this mock:
 * 1. Set stripe.mock.enabled=true in application-local.properties
 * 2. No need for real Stripe API keys
 * 3. All card numbers work, responses are simulated
 */
@ApplicationScoped
@Slf4j
public class MockStripeClient {

    @ConfigProperty(name = "stripe.mock.enabled", defaultValue = "true")
    boolean mockEnabled;

    @ConfigProperty(name = "stripe.mock.simulate-failures", defaultValue = "true")
    boolean simulateFailures;

    public Uni<StripeClient.StripeChargeResult> charge(String paymentMethodToken,
                                                       BigDecimal amount,
                                                       String currency,
                                                       String orderId,
                                                       String idempotencyKey) {

        log.info("🎭 MOCK STRIPE: Processing charge for orderId={}, amount={} {}",
                orderId, amount, currency);

        // Simulate network delay (100-300ms like real Stripe)
        return Uni.createFrom().item(() -> {
            try {
                Thread.sleep((long) (100 + Math.random() * 200));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Simulate different card scenarios based on token
            return simulateCardResponse(paymentMethodToken, amount, currency, orderId);
        });
    }

    public Uni<StripeClient.StripeRefundResult> createRefund(String chargeId,
                                                             BigDecimal amount,
                                                             String reason,
                                                             String idempotencyKey) {

        log.info("🎭 MOCK STRIPE: Processing refund for chargeId={}, amount={}",
                chargeId, amount);

        return Uni.createFrom().item(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            String refundId = "re_mock_" + UUID.randomUUID().toString().substring(0, 8);

            return new StripeClient.StripeRefundResult(
                    refundId,
                    amount,
                    "succeeded"
            );
        });
    }

    public Uni<Map<String, Object>> updateDisputeEvidence(String stripeDisputeId,
                                                          Map<String, Object> evidence) {

        log.info("🎭 MOCK STRIPE: Updating dispute evidence for disputeId={}",
                stripeDisputeId);

        return Uni.createFrom().item(() -> {
            Map<String, Object> response = new HashMap<>();
            response.put("id", stripeDisputeId);
            response.put("status", "under_review");
            response.put("evidence", evidence);
            response.put("updated", Instant.now().getEpochSecond());
            return response;
        });
    }

    public boolean verifyWebhookSignature(String stripeSignatureHeader, byte[] rawBody) {
        log.debug("🎭 MOCK STRIPE: Webhook signature verification (always returns true in mock mode)");
        // In mock mode, always accept webhooks
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SIMULATION LOGIC - Different cards produce different results
    // ─────────────────────────────────────────────────────────────────────────

    private StripeClient.StripeChargeResult simulateCardResponse(String paymentMethodToken,
                                                                 BigDecimal amount,
                                                                 String currency,
                                                                 String orderId) {

        // Simulate different scenarios based on payment method token
        if (paymentMethodToken == null || paymentMethodToken.isEmpty()) {
            log.warn("🎭 MOCK STRIPE: Invalid payment method - charge failed");
            return new StripeClient.StripeChargeResult(
                    false,
                    null,
                    amount,
                    currency,
                    orderId,
                    "invalid_payment_method",
                    "No payment method provided"
            );
        }

        // Simulate card declined scenarios
        if (simulateFailures) {
            if (paymentMethodToken.contains("decline") ||
                    paymentMethodToken.contains("0002")) {
                log.warn("🎭 MOCK STRIPE: Card declined");
                return new StripeClient.StripeChargeResult(
                        false,
                        null,
                        amount,
                        currency,
                        orderId,
                        "card_declined",
                        "Your card was declined"
                );
            }

            if (paymentMethodToken.contains("insufficient") ||
                    paymentMethodToken.contains("9995")) {
                log.warn("🎭 MOCK STRIPE: Insufficient funds");
                return new StripeClient.StripeChargeResult(
                        false,
                        null,
                        amount,
                        currency,
                        orderId,
                        "insufficient_funds",
                        "Your card has insufficient funds"
                );
            }

            if (paymentMethodToken.contains("expired") ||
                    paymentMethodToken.contains("0069")) {
                log.warn("🎭 MOCK STRIPE: Expired card");
                return new StripeClient.StripeChargeResult(
                        false,
                        null,
                        amount,
                        currency,
                        orderId,
                        "expired_card",
                        "Your card has expired"
                );
            }
        }

        // Default: successful charge
        String chargeId = "ch_mock_" + UUID.randomUUID().toString().substring(0, 8);

        log.info("🎭 MOCK STRIPE: ✅ Charge succeeded - chargeId={}", chargeId);

        return new StripeClient.StripeChargeResult(
                true,
                chargeId,
                amount,
                currency,
                orderId,
                null,
                null
        );
    }

}