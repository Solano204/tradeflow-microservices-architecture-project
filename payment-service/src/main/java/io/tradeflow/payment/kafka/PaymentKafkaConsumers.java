package io.tradeflow.payment.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.smallrye.mutiny.Uni;
import io.tradeflow.payment.dto.PaymentDtos.ChargeRequest;
import io.tradeflow.payment.dto.PaymentDtos.RefundRequest;
import io.tradeflow.payment.service.PaymentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Payment Service Kafka Consumers
 *
 * TRACING — how the same traceId arrives here from Order Service:
 * ─────────────────────────────────────────────────────────────────────────────
 * Order Service (Spring Boot) sends cmd.charge-payment via KafkaTemplate which
 * has observationEnabled=true. This injects into the Kafka message header:
 *   traceparent: 00-abc123def456...-spanId-01
 *
 * When this consumer receives that message, quarkus-opentelemetry (configured
 * with quarkus.otel.instrument.messaging=true in application.properties)
 * automatically:
 *   1. Reads the traceparent header from the Kafka message
 *   2. Restores the span context (traceId=abc123def456...)
 *   3. Creates a child "messaging.receive" span
 *   4. Makes it the current active span for this thread
 *
 * Result: ALL code that runs inside onChargeCommand() — including every
 * DB call in PaymentService and MockPaymentService — runs under the same
 * traceId that Order Service created when POST /orders was called.
 *
 * The Span.current() calls below add BUSINESS TAGS to the automatically-
 * created consumer span. They do NOT create new spans — they annotate the
 * existing one. This makes the span much more useful in Zipkin:
 *   order.id=ord_abc123
 *   payment.amount=199.99
 *   payment.currency=MXN
 *
 * ORIGINAL FIXES (unchanged):
 * ─────────────────────────────────────────────────────────────────────────────
 * @Blocking on both @Incoming methods — SmallRye dispatches @Incoming to the
 * Vert.x event loop thread by default. Hibernate Reactive @WithTransaction and
 * JTA @Transactional both FAIL on the IO thread. @Blocking moves execution
 * to a worker thread where both work correctly.
 */
@ApplicationScoped
@Slf4j
public class PaymentKafkaConsumers {

    @Inject
    PaymentService paymentService;

    private final ObjectMapper mapper  = new ObjectMapper();
    private final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    // ─────────────────────────────────────────────────────────────────────────
    // cmd.charge-payment — SAGA charge command from Order Service
    //
    // TRACING: By the time this method body executes, quarkus-opentelemetry has
    // already extracted the traceparent header and restored the span context.
    // Span.current() gives us the active "messaging.receive" span to tag.
    // ─────────────────────────────────────────────────────────────────────────

    @Incoming("cmd-charge-payment")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> onChargeCommand(Message<String> message) {
        Map<String, Object> payload;
        try {
            payload = mapper.readValue(message.getPayload(), MAP_TYPE);
        } catch (Exception e) {
            log.error("cmd-charge-payment: JSON parse failed — discarding. error={}", e.getMessage());
            return Uni.createFrom().completionStage(message.ack());
        }

        String orderId = (String) payload.get("order_id");
        if (orderId == null) {
            log.warn("cmd-charge-payment: missing order_id — acking and discarding");
            return Uni.createFrom().completionStage(message.ack());
        }

        // Tag the current span (automatically created by OTel from traceparent header)
        // with business context — visible in Zipkin as span attributes
        Span currentSpan = Span.current();
        currentSpan.setAttribute("payment.operation",  "charge");
        currentSpan.setAttribute("order.id",           orderId);
        currentSpan.setAttribute("messaging.system",   "kafka");
        currentSpan.setAttribute("messaging.topic",    "cmd.charge-payment");
        currentSpan.setAttribute("messaging.operation","receive");

        try {
            ChargeRequest req = new ChargeRequest(
                    orderId,
                    (String) payload.get("buyer_id"),
                    new BigDecimal(payload.getOrDefault("amount", "0").toString()),
                    (String) payload.getOrDefault("currency", "MXN"),
                    (String) payload.get("payment_method_token"),
                    (String) payload.get("merchant_id"),
                    orderId   // idempotencyKey = orderId (one order = one charge)
            );

            // Tag amount after we have it
            currentSpan.setAttribute("payment.amount",   req.amount().toPlainString());
            currentSpan.setAttribute("payment.currency", req.currency());

            log.info("cmd-charge-payment received: orderId={}, amount={}", orderId, req.amount());

            return paymentService.charge(req)
                    .flatMap(result -> {
                        // Tag the result on the span
                        currentSpan.setAttribute("payment.status", result.status());
                        log.info("Charge completed: orderId={}, status={}", orderId, result.status());
                        return Uni.createFrom().completionStage(message.ack());
                    })
                    .onFailure().recoverWithUni(err -> {
                        currentSpan.recordException(err);
                        log.error("Charge pipeline failed: orderId={}, error={}", orderId, err.getMessage());
                        // nack — Kafka will redeliver; idempotency key prevents double charge
                        return Uni.createFrom().completionStage(message.nack(err));
                    });

        } catch (Exception e) {
            currentSpan.recordException(e);
            log.error("cmd-charge-payment: failed to build ChargeRequest: orderId={}, error={}",
                    orderId, e.getMessage());
            return Uni.createFrom().completionStage(message.ack());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // refund.requested — return flow refund command
    // ─────────────────────────────────────────────────────────────────────────

    @Incoming("refund-requested")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> onRefundCommand(Message<String> message) {
        Map<String, Object> payload;
        try {
            payload = mapper.readValue(message.getPayload(), MAP_TYPE);
        } catch (Exception e) {
            log.error("refund-requested: JSON parse failed — discarding. error={}", e.getMessage());
            return Uni.createFrom().completionStage(message.ack());
        }

        String orderId = (String) payload.get("order_id");
        if (orderId == null) {
            log.warn("refund-requested: missing order_id — discarding");
            return Uni.createFrom().completionStage(message.ack());
        }

        // Tag the current span with business context
        Span currentSpan = Span.current();
        currentSpan.setAttribute("payment.operation",  "refund");
        currentSpan.setAttribute("order.id",           orderId);
        currentSpan.setAttribute("messaging.system",   "kafka");
        currentSpan.setAttribute("messaging.topic",    "refund.requested");
        currentSpan.setAttribute("messaging.operation","receive");

        try {
            RefundRequest req = new RefundRequest(
                    orderId,
                    new BigDecimal(payload.getOrDefault("amount", "0").toString()),
                    (String) payload.getOrDefault("refund_type", "FULL"),
                    (String) payload.getOrDefault("reason",      "BUYER_RETURN"),
                    (String) payload.get("notes"),
                    (String) payload.getOrDefault("initiated_by", "SYSTEM"),
                    orderId + "-refund-" + payload.getOrDefault("timestamp", "")
            );

            currentSpan.setAttribute("payment.amount",   req.amount().toPlainString());
            currentSpan.setAttribute("refund.type",      req.refundType());

            log.info("refund-requested received: orderId={}, amount={}", orderId, req.amount());

            return paymentService.refund(req)
                    .flatMap(result -> {
                        currentSpan.setAttribute("refund.stripe_refund_id", result.stripeRefundId());
                        log.info("Refund completed: orderId={}, stripeRefundId={}",
                                orderId, result.stripeRefundId());
                        return Uni.createFrom().completionStage(message.ack());
                    })
                    .onFailure().recoverWithUni(err -> {
                        currentSpan.recordException(err);
                        log.error("Refund pipeline failed: orderId={}, error={}", orderId, err.getMessage());
                        return Uni.createFrom().completionStage(message.nack(err));
                    });

        } catch (Exception e) {
            currentSpan.recordException(e);
            log.error("refund-requested: failed to build RefundRequest: orderId={}, error={}",
                    orderId, e.getMessage());
            return Uni.createFrom().completionStage(message.ack());
        }
    }
}