package io.tradeflow.payment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import io.tradeflow.payment.dto.PaymentDtos.*;
import io.tradeflow.payment.entity.*;
import io.tradeflow.payment.repository.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * PaymentService
 *
 * ALL jsonb columns are now stored as String to avoid PGobject coercion:
 *   - PaymentOutbox.payload           → serialize Map before storing
 *   - PaymentIdempotency.storedResponse → serialize Map before storing,
 *                                         deserialize when reading back
 *   - Payment.paymentMethodDetails    → already String
 */
@ApplicationScoped
@Slf4j
public class PaymentService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Inject PaymentRepository            paymentRepo;
    @Inject PaymentIdempotencyRepository idempotencyRepo;
    @Inject PaymentOutboxRepository      outboxRepo;
    @Inject PaymentAuditRepository       auditRepo;
    @Inject WebhookEventRepository       webhookRepo;
    @Inject DisputeRepository            disputeRepo;
    @Inject DunningRepository            dunningRepo;
    @Inject MockPaymentService           mockPaymentService;
    @Inject StripeClient                 realStripeClient;
    @Inject MockStripeClient             mockStripeClient;

    @ConfigProperty(name = "stripe.mock.enabled", defaultValue = "true")
    boolean useMockStripe;

    // ─────────────────────────────────────────────────────────────────────────
    // CHARGE — no @WithTransaction here; owned by the delegate
    // ─────────────────────────────────────────────────────────────────────────

    public Uni<ChargeResponse> charge(ChargeRequest request) {
        log.info("Charge request: orderId={}, amount={} {}, mock={}",
                request.orderId(), request.amount(), request.currency(), useMockStripe);

        if (mockPaymentService.isMockMode()) {
            log.info("🎭 MOCK MODE: Simulated charge for orderId={}", request.orderId());
            return mockPaymentService.mockCharge(request);
        }

        return idempotencyRepo.findValidByKey(request.idempotencyKey())
                .onItem().ifNotNull().transform(this::fromIdempotencyRecord)
                .onItem().ifNull().switchTo(() -> processNewCharge(request));
    }

    @WithTransaction
    protected Uni<ChargeResponse> processNewCharge(ChargeRequest request) {
        Payment payment = Payment.builder()
                .id(UUID.randomUUID().toString())
                .orderId(request.orderId())
                .buyerId(request.buyerId())
                .merchantId(request.merchantId())
                .amount(request.amount())
                .currency(request.currency())
                .status(Payment.PaymentStatus.PENDING)
                .paymentMethodToken(request.paymentMethodToken())
                .build();

        return paymentRepo.persist(payment)
                .flatMap(p -> callStripe(request, p))
                .flatMap(result -> handleStripeResult(result, request))
                .flatMap(response -> saveIdempotency(request.idempotencyKey(), request.orderId(), response))
                .flatMap(response -> publishEvent(response, request.orderId()));
    }

    private Uni<StripeClient.StripeChargeResult> callStripe(ChargeRequest request, Payment payment) {
        if (useMockStripe) {
            return mockStripeClient.charge(request.paymentMethodToken(), request.amount(),
                    request.currency(), request.orderId(), request.idempotencyKey());
        }
        return realStripeClient.charge(request.paymentMethodToken(), request.amount(),
                request.currency(), request.orderId(), request.idempotencyKey());
    }

    private Uni<ChargeResponse> handleStripeResult(StripeClient.StripeChargeResult result,
                                                   ChargeRequest request) {
        if (result.succeeded()) {
            return paymentRepo.setStripeCharge(request.orderId(), result.chargeId())
                    .map(p -> new ChargeResponse(p.id, p.orderId, "SUCCEEDED",
                            p.amount, p.currency, result.chargeId(), Instant.now(),
                            request.idempotencyKey()));
        }
        return paymentRepo.updateStatus(request.orderId(), Payment.PaymentStatus.FAILED)
                .map(p -> new ChargeResponse(p.id, p.orderId, "FAILED",
                        p.amount, p.currency, null, Instant.now(), request.idempotencyKey()));
    }

    private Uni<ChargeResponse> saveIdempotency(String key, String orderId, ChargeResponse response) {
        // CRITICAL: serialize Map → JSON String before persisting
        // PaymentIdempotency.storedResponse is String — Map causes PGobject coercion failure
        String storedJson;
        try {
            storedJson = MAPPER.writeValueAsString(toMap(response));
        } catch (Exception e) {
            return Uni.createFrom().failure(
                    new RuntimeException("Failed to serialize idempotency response", e));
        }

        PaymentIdempotency record = PaymentIdempotency.builder()
                .idempotencyKey(key)
                .orderId(orderId)
                .type("CHARGE")
                .storedResponse(storedJson)   // String, not Map
                .expiresAt(Instant.now().plus(Duration.ofDays(7)))
                .build();
        return idempotencyRepo.persist(record).map(__ -> response);
    }

    private Uni<ChargeResponse> publishEvent(ChargeResponse response, String orderId) {
        String eventType = "SUCCEEDED".equals(response.status())
                ? "payment.processed" : "payment.failed";

        Map<String, Object> payloadMap = toMap(response);
        payloadMap.put("event_type", eventType);
        payloadMap.put("order_id",   orderId);

        // CRITICAL: serialize Map → JSON String before persisting
        // PaymentOutbox.payload is String to avoid PGobject coercion failure
        String payloadJson;
        try {
            payloadJson = MAPPER.writeValueAsString(payloadMap);
        } catch (Exception e) {
            return Uni.createFrom().failure(
                    new RuntimeException("Failed to serialize event payload", e));
        }

        PaymentOutbox event = PaymentOutbox.builder()
                .eventType(eventType)
                .payload(payloadJson)   // String, not Map
                .aggregateId(orderId)
                .build();
        return outboxRepo.persist(event).map(__ -> response);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // REFUND
    // ─────────────────────────────────────────────────────────────────────────

    @WithTransaction
    public Uni<RefundResponse> refund(RefundRequest request) {
        log.info("Refund request: orderId={}, amount={}", request.orderId(), request.amount());

        return paymentRepo.findByOrderId(request.orderId())
                .onItem().ifNull().failWith(
                        () -> new NotFoundException("Payment not found: " + request.orderId()))
                .flatMap(payment -> {
                    if (payment.stripeChargeId == null)
                        return Uni.createFrom().failure(
                                new InvalidRefundException("No Stripe charge ID found"));

                    Uni<StripeClient.StripeRefundResult> refundCall = useMockStripe
                            ? mockStripeClient.createRefund(payment.stripeChargeId,
                            request.amount(), request.reason(), request.idempotencyKey())
                            : realStripeClient.createRefund(payment.stripeChargeId,
                            request.amount(), request.reason(), request.idempotencyKey());

                    return refundCall.flatMap(result -> processRefund(result, payment, request));
                });
    }

    private Uni<RefundResponse> processRefund(StripeClient.StripeRefundResult result,
                                              Payment payment, RefundRequest request) {
        Payment.PaymentStatus newStatus = "FULL".equals(request.refundType())
                ? Payment.PaymentStatus.REFUNDED : Payment.PaymentStatus.PARTIAL_REFUND;

        return paymentRepo.updateStatus(payment.orderId, newStatus)
                .map(p -> new RefundResponse(result.refundId(), payment.orderId,
                        result.refundId(), request.amount(), result.status(),
                        "5-10 business days", Instant.now()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Other endpoints
    // ─────────────────────────────────────────────────────────────────────────

    public Uni<Void> processWebhook(StripeWebhookEvent event) {
        return Uni.createFrom().voidItem();
    }

    public Uni<PaymentStatusResponse> getPaymentStatus(String orderId) {
        return paymentRepo.findByOrderId(orderId)
                .onItem().ifNull().failWith(() -> new NotFoundException("Payment not found"))
                .map(p -> new PaymentStatusResponse(p.id, p.orderId, p.status.name(),
                        p.amount, p.currency, p.stripeChargeId, null,
                        p.processedAt, List.of(), null));
    }

    public Uni<ReceiptResponse> getReceipt(String orderId, String buyerId) {
        return paymentRepo.findByOrderId(orderId)
                .onItem().ifNull().failWith(() -> new NotFoundException("Payment not found"))
                .map(p -> new ReceiptResponse("receipt_" + orderId, orderId,
                        p.createdAt, null, null, List.of(), null, null));
    }

    public Uni<Void> dunningTick() {
        return Uni.createFrom().voidItem();
    }

    public Uni<InternalPaymentStatusResponse> getInternalStatus(String orderId) {
        return paymentRepo.findByOrderId(orderId)
                .map(p -> new InternalPaymentStatusResponse(orderId, true,
                        p.status.name(), p.stripeChargeId, p.amount, p.processedAt))
                .onItem().ifNull().continueWith(new InternalPaymentStatusResponse(
                        orderId, false, null, null, null, null));
    }

    public Uni<Void> respondToDispute(String disputeId, DisputeEvidenceRequest request) {
        return Uni.createFrom().voidItem();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Read idempotency record back.
     * storedResponse is now a JSON String — must deserialize with Jackson.
     */
    private ChargeResponse fromIdempotencyRecord(PaymentIdempotency record) {
        try {
            // CRITICAL: storedResponse is String — deserialize back to Map
            Map<String, Object> s = MAPPER.readValue(record.storedResponse, MAP_TYPE);
            return new ChargeResponse(
                    (String) s.get("paymentId"),
                    (String) s.get("orderId"),
                    (String) s.get("status"),
                    new BigDecimal(s.get("amount").toString()),
                    (String) s.get("currency"),
                    (String) s.get("stripeChargeId"),
                    Instant.parse((String) s.get("processedAt")),
                    (String) s.get("idempotencyKey"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize idempotency record: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> toMap(ChargeResponse r) {
        Map<String, Object> m = new HashMap<>();
        m.put("paymentId",      r.paymentId());
        m.put("orderId",        r.orderId());
        m.put("order_id",       r.orderId());
        m.put("status",         r.status());
        m.put("amount",         r.amount().toPlainString());
        m.put("currency",       r.currency());
        m.put("stripeChargeId", r.stripeChargeId());
        m.put("charge_id",      r.stripeChargeId());
        m.put("processedAt",    r.processedAt().toString());
        m.put("idempotencyKey", r.idempotencyKey());
        return m;
    }
}