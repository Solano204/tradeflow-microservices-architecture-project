package io.tradeflow.fraud.kafka;

import io.micrometer.tracing.Tracer;
import io.tradeflow.fraud.dto.FraudDtos.*;
import io.tradeflow.fraud.service.FraudScoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * FraudKafkaConsumers — receives scoring commands and confirmed fraud events.
 *
 * TRACING NOTES:
 *
 * The kafkaListenerContainerFactory has setObservationEnabled(true) (FraudConfig).
 * This means when a message arrives from Order Service (cmd.score-transaction),
 * the W3C "traceparent" header is extracted automatically and the current span
 * CONTINUES the Order Service's trace (same traceId).
 *
 * Result in Zipkin for a full order flow:
 *   [Order Service: POST /orders]
 *     → [Order Service: KafkaTemplate.send cmd.score-transaction]
 *       → [Fraud Service: onScoreTransaction]           ← SAME traceId
 *         → [Fraud Service: FraudScoringService.score]  ← SAME traceId
 *           → [MongoDB: $vectorSearch]                  ← SAME traceId
 *           → [PostgreSQL: fraud_scores_audit INSERT]   ← SAME traceId
 *           → [Redis: SISMEMBER ofac:ips]               ← SAME traceId
 *           → [Spring AI: embedding HTTP call]          ← SAME traceId
 *         → [Fraud Service: KafkaTemplate.send fraud.events] ← SAME traceId
 *           → [Order Service: onFraudResult]            ← SAME traceId
 *
 * We inject Tracer only to add BUSINESS TAGS for Zipkin search:
 *   fraud.order_id, fraud.buyer_id, fraud.decision, fraud.score, etc.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FraudKafkaConsumers {

    private final FraudScoringService scoringService;
    private final Tracer tracer; // ✅ injected to tag the current span

    // ─────────────────────────────────────────────────────────────────────────
    // cmd.score-transaction — primary SAGA trigger from Order Service
    //
    // This is the most important consumer for trace continuity.
    // The traceId that started in Order Service's POST /orders is extracted
    // from the Kafka message headers and becomes the current span's traceId.
    // Everything that FraudScoringService.score() does is a child of this span.
    // ─────────────────────────────────────────────────────────────────────────

    @KafkaListener(
            topics = "cmd.score-transaction",
            groupId = "fraud-scoring",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onScoreTransaction(ConsumerRecord<String, Map<String, Object>> record, Acknowledgment ack) {
        Map<String, Object> payload = record.value();
        String orderId  = (String) payload.get("order_id");
        String buyerId  = (String) payload.get("buyer_id");
        Object amtRaw   = payload.getOrDefault("amount", "0");

        if (orderId == null) {
            log.warn("cmd.score-transaction missing order_id — discarding");
            ack.acknowledge();
            return;
        }

        // ✅ Tag the current span (traceId was extracted from Kafka headers automatically)
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("messaging.system",      "kafka");
            span.tag("messaging.destination", "cmd.score-transaction");
            span.tag("messaging.operation",   "score-command");
            span.tag("fraud.order_id",        orderId);
            span.tag("fraud.buyer_id",        buyerId != null ? buyerId : "unknown");
            span.tag("fraud.amount",          amtRaw.toString());
        }

        log.info("[traceId={}] cmd.score-transaction received: orderId={}, amount={}",
                span != null ? span.context().traceId() : "none", orderId, amtRaw);

        try {
            ScoreRequest req = buildScoreRequest(payload);
            ScoreResponse result = scoringService.score(req);

            // ✅ Tag the outcome on the span
            if (span != null) {
                span.tag("fraud.score",    String.valueOf(result.score()));
                span.tag("fraud.decision", result.decision());
            }

            log.info("[traceId={}] Scored: orderId={}, score={}, decision={}",
                    span != null ? span.context().traceId() : "none",
                    orderId, result.score(), result.decision());

            ack.acknowledge();

        } catch (Exception e) {
            if (span != null) span.error(e); // ✅ record exception on the span (shows red in Zipkin)
            log.error("[traceId={}] Scoring failed: orderId={}, error={}",
                    span != null ? span.context().traceId() : "none", orderId, e.getMessage(), e);
            // No ack — Kafka will redeliver. FraudScoreAudit has UNIQUE on order_id = idempotent.
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // fraud.confirmed — from Payment Service (dispute won = confirmed fraud)
    //
    // The traceId from Payment Service's dispute processing continues here.
    // FeedbackRequest updates MongoDB fraud_vectors + buyer baseline.
    // Both DB operations appear as child spans of this consumer span.
    // ─────────────────────────────────────────────────────────────────────────

    @KafkaListener(
            topics = "fraud.confirmed",
            groupId = "fraud-feedback",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onFraudConfirmed(ConsumerRecord<String, Map<String, Object>> record, Acknowledgment ack) {
        Map<String, Object> payload = record.value();
        String orderId   = (String) payload.get("order_id");
        String fraudType = (String) payload.getOrDefault("fraud_type", "UNKNOWN");

        if (orderId == null) {
            ack.acknowledge();
            return;
        }

        // ✅ Tag the current span
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("messaging.system",      "kafka");
            span.tag("messaging.destination", "fraud.confirmed");
            span.tag("messaging.operation",   "feedback-loop");
            span.tag("fraud.order_id",        orderId);
            span.tag("fraud.type",            fraudType);
        }

        try {
            FeedbackRequest req = new FeedbackRequest(
                    orderId,
                    fraudType,
                    "PAYMENT_SERVICE",
                    "DISPUTE_WON",
                    payload.get("original_score") != null
                            ? ((Number) payload.get("original_score")).doubleValue() : null,
                    (String) payload.get("original_decision"),
                    "Confirmed via Stripe dispute resolution"
            );

            log.info("[traceId={}] fraud.confirmed received: orderId={}, type={}",
                    span != null ? span.context().traceId() : "none", orderId, req.confirmedFraudType());

            scoringService.processFeedback(req);
            ack.acknowledge();

        } catch (Exception e) {
            if (span != null) span.error(e);
            log.error("[traceId={}] Feedback processing failed: orderId={}, error={}",
                    span != null ? span.context().traceId() : "none", orderId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private ScoreRequest buildScoreRequest(Map<String, Object> payload) {
        Object amtRaw     = payload.getOrDefault("amount", "0");
        BigDecimal amount = new BigDecimal(amtRaw.toString());
        Object productIds  = payload.get("product_ids");
        Object categories  = payload.get("product_categories");

        return new ScoreRequest(
                (String) payload.get("order_id"),
                (String) payload.get("buyer_id"),
                (String) payload.get("merchant_id"),
                amount,
                (String) payload.getOrDefault("currency", "MXN"),
                productIds  instanceof List ? (List<String>) productIds  : List.of(),
                categories  instanceof List ? (List<String>) categories  : List.of(),
                (String) payload.get("device_fingerprint"),
                (String) payload.get("ip_address"),
                (String) payload.get("user_agent"),
                (String) payload.get("payment_method_token")
        );
    }
}