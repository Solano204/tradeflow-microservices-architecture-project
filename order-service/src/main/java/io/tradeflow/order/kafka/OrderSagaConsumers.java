package io.tradeflow.order.kafka;

import io.micrometer.observation.annotation.Observed;
import io.tradeflow.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Order Service Kafka Consumers — The SAGA Nervous System
 *
 * TRACING:
 * ─────────────────────────────────────────────────────────────────────────────
 * Each @KafkaListener method is annotated with @Observed. Combined with
 * TracingConfig.observedKafkaListenerContainerFactory() which enables
 * container-level observation, this produces two layers of spans in Zipkin:
 *
 *   Layer 1 (container): "kafka.consume" span — created automatically when
 *     the container reads the Kafka message and extracts the traceparent header.
 *     The traceId from the producing service is restored here.
 *
 *   Layer 2 (@Observed): named business span — e.g. "saga.consumer.inventory"
 *     created as a child of layer 1. All DB work inside the handler (appendEvent,
 *     orderRepo.save, outboxRepo.save) appears as children of this span.
 *
 * Result in Zipkin for one order:
 *   [order-service] http POST /orders                          traceId=abc123
 *     [order-service] postgresql INSERT orders                 traceId=abc123
 *     [order-service] kafka send cmd.reserve-inventory        traceId=abc123
 *       [inventory-service] kafka.consume inventory.events    traceId=abc123  ← same!
 *         [inventory-service] postgresql UPDATE inventory      traceId=abc123
 *         [inventory-service] kafka send inventory.events      traceId=abc123
 *           [order-service] kafka.consume inventory.events     traceId=abc123  ← still same!
 *             [order-service] saga.consumer.inventory          traceId=abc123
 *               [order-service] postgresql INSERT order_events traceId=abc123
 *               [order-service] kafka send cmd.score-transaction traceId=abc123
 *
 * Consumer groups:
 *   order-saga-inventory   → inventory.events
 *   order-saga-fraud       → fraud.events
 *   order-saga-payment     → payment.events
 *
 * FIX NOTE: Inventory Service outbox payloads do not include an "event_type"
 * field inside the JSON body. We infer the event type from payload shape:
 *   - has "reservation_id" + "expires_at"  → inventory.reserved
 *   - has "reason" + "qty_released"        → inventory.released
 *   - has "reservation_id" (no expires_at) → inventory.confirmed
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderSagaConsumers {

    private final OrderService orderService;

    // ─────────────────────────────────────────────────────────────────────────
    // Consumer 1: inventory.events
    // ─────────────────────────────────────────────────────────────────────────

    @KafkaListener(
            topics = "inventory.events",
            groupId = "order-saga-inventory",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Observed(name = "saga.consumer.inventory",
            contextualName = "saga-inventory-event")
    public void onInventoryEvent(ConsumerRecord<String, Map<String, Object>> record, Acknowledgment ack) {
        Map<String, Object> payload = record.value();

        if (payload == null) {
            log.warn("inventory.events: received null payload — acking and skipping");
            ack.acknowledge();
            return;
        }

        // Resolve event type — Inventory Service does NOT embed "event_type" in payload body.
        // Try reading it first, then infer from payload shape as fallback.
        String eventType = (String) payload.get("event_type");

        if (eventType == null) {
            if (payload.containsKey("reservation_id") && payload.containsKey("expires_at")) {
                eventType = "inventory.reserved";
                log.debug("inventory.events: inferred event_type=inventory.reserved from payload shape");
            } else if (payload.containsKey("qty_released") || payload.containsKey("reason")) {
                eventType = "inventory.released";
                log.debug("inventory.events: inferred event_type=inventory.released from payload shape");
            } else if (payload.containsKey("reservation_id") && payload.containsKey("qty_sold")) {
                eventType = "inventory.confirmed";
                log.debug("inventory.events: inferred event_type=inventory.confirmed from payload shape");
            } else {
                log.debug("inventory.events: no event_type and unrecognised payload shape — skipping. payload={}",
                        payload);
                ack.acknowledge();
                return;
            }
        }

        String orderId = (String) payload.get("order_id");
        if (orderId == null) {
            log.debug("inventory.events: no order_id in payload (event_type={}) — skipping", eventType);
            ack.acknowledge();
            return;
        }

        try {
            switch (eventType) {

                case "inventory.reserved" -> {
                    String reservationId = (String) payload.get("reservation_id");
                    int qty = ((Number) payload.getOrDefault("qty", 0)).intValue();
                    log.info("SAGA: inventory.reserved received: orderId={}, reservationId={}, qty={}",
                            orderId, reservationId, qty);
                    orderService.onInventoryReserved(orderId, reservationId, qty, record.key());
                }

                case "inventory.released" -> {
                    log.info("SAGA: inventory.released received: orderId={}", orderId);
                    orderService.onInventoryReleased(orderId, record.key());
                }

                case "inventory.confirmed" -> {
                    log.debug("SAGA: inventory.confirmed received (no action needed): orderId={}", orderId);
                }

                default -> log.debug("SAGA: ignoring inventory event type='{}' for orderId={}",
                        eventType, orderId);
            }

            ack.acknowledge();

        } catch (Exception e) {
            log.error("SAGA: failed processing inventory event type={} orderId={}: {}",
                    eventType, orderId, e.getMessage(), e);
            // Do NOT ack — Kafka will redeliver. Handlers are idempotent so safe to retry.
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Consumer 2: fraud.events
    // ─────────────────────────────────────────────────────────────────────────

    @KafkaListener(
            topics = "fraud.events",
            groupId = "order-saga-fraud",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Observed(name = "saga.consumer.fraud",
            contextualName = "saga-fraud-event")
    public void onFraudEvent(ConsumerRecord<String, Map<String, Object>> record, Acknowledgment ack) {
        Map<String, Object> payload = record.value();

        if (payload == null) {
            log.warn("fraud.events: received null payload — acking and skipping");
            ack.acknowledge();
            return;
        }

        String eventType = (String) payload.getOrDefault("event_type", "fraud.score.computed");
        String orderId   = (String) payload.get("order_id");

        if (!"fraud.score.computed".equals(eventType) || orderId == null) {
            log.debug("fraud.events: skipping event_type='{}' orderId={}", eventType, orderId);
            ack.acknowledge();
            return;
        }

        try {
            double score    = ((Number) payload.getOrDefault("score", 0)).doubleValue();
            String decision = (String) payload.getOrDefault("decision", "REVIEW");

            log.info("SAGA: fraud.score.computed received: orderId={}, score={}, decision={}",
                    orderId, score, decision);
            orderService.onFraudScoreComputed(orderId, score, decision, record.key());
            ack.acknowledge();

        } catch (Exception e) {
            log.error("SAGA: failed processing fraud event orderId={}: {}", orderId, e.getMessage(), e);
            // Do NOT ack — let Kafka retry
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Consumer 3: payment.events
    // ─────────────────────────────────────────────────────────────────────────

    @KafkaListener(
            topics = "payment.events",
            groupId = "order-saga-payment",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Observed(name = "saga.consumer.payment",
            contextualName = "saga-payment-event")
    public void onPaymentEvent(ConsumerRecord<String, Map<String, Object>> record, Acknowledgment ack) {
        Map<String, Object> payload = record.value();

        if (payload == null) {
            log.warn("payment.events: received null payload — acking and skipping");
            ack.acknowledge();
            return;
        }

        String eventType = (String) payload.get("event_type");

        // Resolve order_id — MockPaymentService uses camelCase "orderId"
        String orderId = (String) payload.get("order_id");
        if (orderId == null) {
            orderId = (String) payload.get("orderId");
        }

        if (orderId == null) {
            log.debug("payment.events: no order_id in payload — skipping. eventType={}", eventType);
            ack.acknowledge();
            return;
        }

        try {
            switch (eventType != null ? eventType : "") {

                case "payment.processed" -> {
                    // MockPaymentService uses "stripeChargeId", real Stripe uses "charge_id"
                    String chargeId = (String) payload.get("charge_id");
                    if (chargeId == null) chargeId = (String) payload.get("stripeChargeId");

                    Object amtRaw = payload.getOrDefault("amount", "0");
                    BigDecimal amount;
                    try {
                        amount = new BigDecimal(amtRaw.toString());
                    } catch (NumberFormatException ex) {
                        log.warn("payment.events: could not parse amount='{}' — defaulting to 0", amtRaw);
                        amount = BigDecimal.ZERO;
                    }

                    log.info("SAGA: payment.processed received: orderId={}, chargeId={}, amount={}",
                            orderId, chargeId, amount);
                    orderService.onPaymentProcessed(orderId, chargeId, amount, record.key());
                }

                case "payment.failed" -> {
                    String reason = (String) payload.getOrDefault("reason", "UNKNOWN");
                    log.warn("SAGA: payment.failed received: orderId={}, reason={}", orderId, reason);
                    orderService.onPaymentFailed(orderId, reason, record.key());
                }

                default -> log.debug("SAGA: ignoring payment event type='{}' orderId={}", eventType, orderId);
            }

            ack.acknowledge();

        } catch (Exception e) {
            log.error("SAGA: failed processing payment event type={} orderId={}: {}",
                    eventType, orderId, e.getMessage(), e);
            // Do NOT ack — let Kafka retry
        }
    }
}