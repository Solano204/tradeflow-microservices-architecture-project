package io.tradeflow.payment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import io.tradeflow.payment.dto.PaymentDtos.*;
import io.tradeflow.payment.entity.Payment;
import io.tradeflow.payment.entity.PaymentAuditRecord;
import io.tradeflow.payment.entity.PaymentIdempotency;
import io.tradeflow.payment.entity.PaymentOutbox;
import io.tradeflow.payment.repository.PaymentAuditRepository;
import io.tradeflow.payment.repository.PaymentIdempotencyRepository;
import io.tradeflow.payment.repository.PaymentOutboxRepository;
import io.tradeflow.payment.repository.PaymentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MockPaymentService — simulates Stripe without real API calls.
 *
 * TRACING:
 * ─────────────────────────────────────────────────────────────────────────────
 * This service is called from PaymentKafkaConsumers.onChargeCommand() which runs
 * INSIDE a span context already restored by quarkus-opentelemetry from the Kafka
 * message traceparent header. That means the traceId from Order Service is already
 * active when any method in this class executes.
 *
 * We use Span.current() to tag the EXISTING span with business context.
 * We do NOT create new spans manually — quarkus-opentelemetry already creates
 * child spans for every @WithTransaction DB operation automatically.
 *
 * Zipkin will show:
 *   messaging.receive cmd.charge-payment    [traceId=abc123]  ← Order Service's trace
 *     └── postgresql INSERT payments        [traceId=abc123]  ← auto child span
 *     └── mongodb INSERT payment_audit      [traceId=abc123]  ← auto child span
 *     └── postgresql INSERT payment_outbox  [traceId=abc123]  ← auto child span
 *     └── emitter.send payment.events       [traceId=abc123]  ← OTel injects header
 *
 * ORIGINAL FIXES (all unchanged):
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. MongoDB payment_audit write — now written on every successful mock charge
 * 2. String jsonb columns — PaymentOutbox.payload and PaymentIdempotency.storedResponse
 *    are String to avoid PGobject coercion in Vert.x reactive PG client
 * 3. event_type in payload — both payment.processed and payment.failed include
 *    event_type so all downstream consumers can route correctly
 */
@ApplicationScoped
@Slf4j
public class MockPaymentService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Inject PaymentRepository            paymentRepository;
    @Inject PaymentIdempotencyRepository idempotencyRepository;
    @Inject PaymentOutboxRepository      outboxRepository;
    @Inject PaymentAuditRepository       auditRepository;

    @ConfigProperty(name = "stripe.mock.enabled",           defaultValue = "false")
    boolean mockEnabled;

    @ConfigProperty(name = "stripe.mock.simulate-failures", defaultValue = "false")
    boolean simulateFailures;

    // ─────────────────────────────────────────────────────────────────────────
    // MOCK CHARGE
    // ─────────────────────────────────────────────────────────────────────────

    @WithTransaction
    public Uni<ChargeResponse> mockCharge(ChargeRequest request) {
        log.info("MOCK CHARGE: orderId={}, amount={} {}",
                request.orderId(), request.amount(), request.currency());

        // Tag the current active span (from Kafka traceparent header) with payment context
        Span currentSpan = Span.current();
        currentSpan.setAttribute("payment.mock",      true);
        currentSpan.setAttribute("payment.operation", "mock-charge");
        currentSpan.setAttribute("order.id",          request.orderId());
        currentSpan.setAttribute("payment.amount",    request.amount().toPlainString());
        currentSpan.setAttribute("payment.currency",  request.currency());

        return idempotencyRepository.findValidByKey(request.idempotencyKey())
                .flatMap(cached -> {
                    if (cached != null) {
                        log.info("MOCK CHARGE: Idempotency hit — orderId={}", request.orderId());
                        currentSpan.setAttribute("payment.idempotent", true);
                        try {
                            Map<String, Object> r = MAPPER.readValue(cached.storedResponse, MAP_TYPE);
                            return Uni.createFrom().item(new ChargeResponse(
                                    (String) r.get("paymentId"),
                                    request.orderId(),
                                    (String) r.get("status"),
                                    request.amount(),
                                    request.currency(),
                                    (String) r.get("stripeChargeId"),
                                    Instant.parse((String) r.get("processedAt")),
                                    request.idempotencyKey()
                            ));
                        } catch (Exception e) {
                            log.warn("MOCK CHARGE: Failed to parse idempotency cache: {}", e.getMessage());
                            return createNewCharge(request, currentSpan);
                        }
                    }
                    return createNewCharge(request, currentSpan);
                });
    }

    private Uni<ChargeResponse> createNewCharge(ChargeRequest request, Span currentSpan) {
        boolean chargeSucceeds     = !simulateFailures || Math.random() > 0.3;
        String  paymentId          = "pay_"     + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String  mockStripeChargeId = "ch_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Instant now                = Instant.now();

        // Tag the span with the result before any async work
        currentSpan.setAttribute("payment.stripe_charge_id", mockStripeChargeId);
        currentSpan.setAttribute("payment.succeeded",        chargeSucceeds);

        log.info("MOCK CHARGE simulated: orderId={}, chargeSucceeds={}, chargeId={}",
                request.orderId(), chargeSucceeds, mockStripeChargeId);

        Payment payment = Payment.builder()
                .id(paymentId)
                .orderId(request.orderId())
                .buyerId(request.buyerId())
                .merchantId(request.merchantId())
                .amount(request.amount())
                .currency(request.currency())
                .status(chargeSucceeds ? Payment.PaymentStatus.SUCCEEDED : Payment.PaymentStatus.FAILED)
                .stripeChargeId(mockStripeChargeId)
                .paymentMethodToken(request.paymentMethodToken())
                // String → jsonb works cleanly with Vert.x reactive PG client
                .paymentMethodDetails(
                        "{\"type\":\"card\",\"brand\":\"mock_visa\"," +
                                "\"last4\":\"4242\",\"exp_month\":12,\"exp_year\":2025}"
                )
                .processedAt(now)
                .build();

        return paymentRepository.persist(payment)
                .flatMap(p -> writeAuditRecord(p, mockStripeChargeId, request, now, chargeSucceeds))
                .flatMap(p -> publishChargeEvent(p, chargeSucceeds, mockStripeChargeId, request, now));
    }

    /**
     * Write payment_audit record to MongoDB.
     * quarkus-opentelemetry auto-creates a child span for this MongoDB operation.
     * That child span shares the same traceId — visible in Zipkin under the trace.
     */
    private Uni<Payment> writeAuditRecord(Payment payment,
                                          String mockStripeChargeId,
                                          ChargeRequest request,
                                          Instant now,
                                          boolean chargeSucceeds) {
        if (!chargeSucceeds) {
            return Uni.createFrom().item(payment);
        }

        Map<String, Object> rawStripeResponse = new HashMap<>();
        rawStripeResponse.put("id",       mockStripeChargeId);
        rawStripeResponse.put("object",   "charge");
        rawStripeResponse.put("amount",   request.amount().movePointRight(2).longValue());
        rawStripeResponse.put("currency", request.currency().toLowerCase());
        rawStripeResponse.put("status",   "succeeded");
        rawStripeResponse.put("paid",     true);
        rawStripeResponse.put("mock",     true);
        rawStripeResponse.put("created",  now.getEpochSecond());
        rawStripeResponse.put("metadata", Map.of(
                "order_id",        request.orderId(),
                "idempotency_key", request.idempotencyKey()
        ));
        rawStripeResponse.put("payment_method_details", Map.of(
                "type",  "card",
                "brand", "mock_visa",
                "last4", "4242"
        ));

        PaymentAuditRecord auditRecord = PaymentAuditRecord.builder()
                .orderId(request.orderId())
                .stripeChargeId(mockStripeChargeId)
                .amount(request.amount())
                .currency(request.currency())
                .buyerId(request.buyerId())
                .merchantId(request.merchantId())
                .rawStripeResponse(rawStripeResponse)
                .recordedAt(now)
                .build();

        return auditRepository.persist(auditRecord)
                .map(saved -> {
                    log.info("MOCK CHARGE: MongoDB audit record saved: orderId={}, chargeId={}",
                            request.orderId(), mockStripeChargeId);
                    return payment;
                })
                .onFailure().recoverWithItem(err -> {
                    // Audit write failure must NOT fail the charge — log and continue
                    log.error("MOCK CHARGE: MongoDB audit write failed (non-fatal): {}",
                            err.getMessage());
                    return payment;
                });
    }

    private Uni<ChargeResponse> publishChargeEvent(Payment payment,
                                                   boolean chargeSucceeds,
                                                   String mockStripeChargeId,
                                                   ChargeRequest request,
                                                   Instant now) {
        String eventType = chargeSucceeds ? "payment.processed" : "payment.failed";

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("event_type",     eventType);          // routing key for all consumers
        payloadMap.put("order_id",       request.orderId());  // snake_case (Order, Notification)
        payloadMap.put("orderId",        request.orderId());  // camelCase (legacy)
        payloadMap.put("charge_id",      mockStripeChargeId); // snake_case
        payloadMap.put("stripeChargeId", mockStripeChargeId); // camelCase
        payloadMap.put("paymentId",      payment.id);
        payloadMap.put("buyer_id",       request.buyerId());
        payloadMap.put("buyerId",        request.buyerId());
        payloadMap.put("merchant_id",    request.merchantId());
        payloadMap.put("merchantId",     request.merchantId());
        payloadMap.put("amount",         request.amount().toPlainString());
        payloadMap.put("currency",       request.currency());
        payloadMap.put("status",         chargeSucceeds ? "SUCCEEDED" : "FAILED");
        payloadMap.put("processedAt",    now.toString());
        payloadMap.put("timestamp",      now.toString());
        if (!chargeSucceeds) payloadMap.put("reason", "MOCK_PAYMENT_FAILED");

        // TRACING NOTE:
        // We do NOT manually inject traceparent here.
        // When OutboxRelayService sends this payload via its Emitter, quarkus-opentelemetry
        // automatically injects the current span's traceparent into the Kafka message headers.
        // Order Service's KafkaTemplate consumer will then extract it and continue the trace.

        String payloadJson;
        try {
            payloadJson = MAPPER.writeValueAsString(payloadMap);
        } catch (Exception e) {
            return Uni.createFrom().failure(
                    new RuntimeException("Failed to serialize payment payload", e));
        }

        PaymentOutbox outbox = PaymentOutbox.builder()
                .eventType(eventType)
                .payload(payloadJson)   // String, not Map — avoids PGobject coercion
                .aggregateId(request.orderId())
                .published(false)
                .build();

        log.info("MOCK CHARGE: Writing outbox event={} for orderId={}", eventType, request.orderId());

        return outboxRepository.persist(outbox)
                .flatMap(saved -> cacheIdempotencyKey(payment, chargeSucceeds,
                        mockStripeChargeId, request, now));
    }

    private Uni<ChargeResponse> cacheIdempotencyKey(Payment payment,
                                                    boolean chargeSucceeds,
                                                    String mockStripeChargeId,
                                                    ChargeRequest request,
                                                    Instant now) {
        Map<String, Object> cachedMap = new HashMap<>();
        cachedMap.put("paymentId",      payment.id);
        cachedMap.put("orderId",        request.orderId());
        cachedMap.put("status",         chargeSucceeds ? "SUCCEEDED" : "FAILED");
        cachedMap.put("amount",         request.amount().toPlainString());
        cachedMap.put("currency",       request.currency());
        cachedMap.put("stripeChargeId", mockStripeChargeId);
        cachedMap.put("processedAt",    now.toString());

        String cachedJson;
        try {
            cachedJson = MAPPER.writeValueAsString(cachedMap);
        } catch (Exception e) {
            return Uni.createFrom().failure(
                    new RuntimeException("Failed to serialize idempotency response", e));
        }

        PaymentIdempotency idempotency = PaymentIdempotency.builder()
                .idempotencyKey(request.idempotencyKey())
                .orderId(request.orderId())
                .type("CHARGE")
                .storedResponse(cachedJson)   // String, not Map
                .expiresAt(now.plusSeconds(7L * 24 * 3600))
                .build();

        return idempotencyRepository.persist(idempotency)
                .map(saved -> {
                    log.info("MOCK CHARGE: Complete — orderId={}, status={}",
                            request.orderId(), chargeSucceeds ? "SUCCEEDED" : "FAILED");
                    return new ChargeResponse(
                            payment.id, request.orderId(),
                            chargeSucceeds ? "SUCCEEDED" : "FAILED",
                            request.amount(), request.currency(),
                            mockStripeChargeId, now, request.idempotencyKey());
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MOCK REFUND
    // ─────────────────────────────────────────────────────────────────────────

    @WithTransaction
    public Uni<RefundResponse> mockRefund(RefundRequest request) {
        log.info("MOCK REFUND: orderId={}, amount={}", request.orderId(), request.amount());

        Span currentSpan = Span.current();
        currentSpan.setAttribute("payment.mock",      true);
        currentSpan.setAttribute("payment.operation", "mock-refund");
        currentSpan.setAttribute("order.id",          request.orderId());
        currentSpan.setAttribute("payment.amount",    request.amount().toPlainString());

        String  refundId           = "ref_"     + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String  mockStripeRefundId = "re_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Instant now                = Instant.now();

        currentSpan.setAttribute("refund.stripe_refund_id", mockStripeRefundId);

        return paymentRepository.findByOrderId(request.orderId())
                .onItem().ifNull().failWith(
                        () -> new NotFoundException("Payment not found for order: " + request.orderId()))
                .flatMap(payment -> {
                    payment.status    = Payment.PaymentStatus.PARTIAL_REFUND;
                    payment.updatedAt = now;

                    Map<String, Object> refundMap = new HashMap<>();
                    refundMap.put("event_type",     "refund.completed");
                    refundMap.put("order_id",       request.orderId());
                    refundMap.put("orderId",        request.orderId());
                    refundMap.put("refundId",       refundId);
                    refundMap.put("stripeRefundId", mockStripeRefundId);
                    refundMap.put("amount",         request.amount().toPlainString());
                    refundMap.put("reason",         request.reason());
                    refundMap.put("processedAt",    now.toString());
                    refundMap.put("timestamp",      now.toString());

                    String refundJson;
                    try {
                        refundJson = MAPPER.writeValueAsString(refundMap);
                    } catch (Exception e) {
                        return Uni.createFrom().failure(
                                new RuntimeException("Failed to serialize refund payload", e));
                    }

                    PaymentOutbox outbox = PaymentOutbox.builder()
                            .eventType("refund.completed")
                            .payload(refundJson)   // String, not Map
                            .aggregateId(request.orderId())
                            .published(false)
                            .build();

                    Map<String, Object> refundAudit = Map.of(
                            "refundId",       refundId,
                            "stripeRefundId", mockStripeRefundId,
                            "amount",         request.amount().toPlainString(),
                            "reason",         request.reason(),
                            "processedAt",    now.toString()
                    );

                    return outboxRepository.persist(outbox)
                            .flatMap(saved -> auditRepository.appendRefundRecord(
                                    request.orderId(), refundAudit))
                            .map(v -> {
                                log.info("MOCK REFUND: Complete — orderId={}", request.orderId());
                                return new RefundResponse(
                                        refundId, request.orderId(), mockStripeRefundId,
                                        request.amount(), "SUCCEEDED",
                                        now.plusSeconds(7L * 24 * 3600).toString(), now);
                            });
                });
    }

    public boolean isMockMode() {
        return mockEnabled;
    }
}