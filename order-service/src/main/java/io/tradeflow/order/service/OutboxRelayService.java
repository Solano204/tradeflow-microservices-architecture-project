package io.tradeflow.order.service;

import io.tradeflow.order.entity.OrderOutbox;
import io.tradeflow.order.repository.OrderOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * OutboxRelayService — polls order_outbox every 500ms and publishes to Kafka.
 *
 * TRACING — how it works automatically:
 * ─────────────────────────────────────────────────────────────────────────────
 * No tracing code is needed in this class. Here's why:
 *
 * The KafkaTemplate was configured in InfrastructureConfig with:
 *   template.setObservationEnabled(true)
 *
 * This means every call to kafkaTemplate.send(...) automatically:
 *   1. Reads the current active span from the Micrometer context
 *   2. Creates a child "kafka.send" span
 *   3. Injects the W3C traceparent header into the Kafka message
 *
 * The relay runs in a @Scheduled thread. If a Kafka consumer (e.g. onInventoryReserved)
 * wrote the outbox row, the span context is propagated via Spring's thread-local
 * mechanism through the @Transactional boundary.
 *
 * Result: every Kafka message sent by this relay carries the traceId of whatever
 * business operation triggered the outbox write — whether HTTP or Kafka consumer.
 *
 * Kafka topic routing:
 * ─────────────────────────────────────────────────────────────────────────────
 *   cmd.*         → dedicated command topics (Inventory, Fraud, Payment)
 *   event.*       → order.events (Notification, Analytics consume this)
 *   notification.* → notifications topic (Notification Service consumes this)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxRelayService {

    private final OrderOutboxRepository outboxRepo;
    private final KafkaTemplate<String, Map<String, Object>> kafkaTemplate;

    private static final Map<String, String> TOPIC_MAP = Map.ofEntries(
            Map.entry("cmd.reserve-inventory",                   "cmd.reserve-inventory"),
            Map.entry("cmd.score-transaction",                   "cmd.score-transaction"),
            Map.entry("cmd.charge-payment",                      "cmd.charge-payment"),
            Map.entry("cmd.release-inventory",                   "cmd.release-inventory"),
            Map.entry("cmd.confirm-inventory",                   "cmd.confirm-inventory"),
            Map.entry("event.order-paid",                        "order.events"),
            Map.entry("event.order-cancelled",                   "order.events"),
            Map.entry("event.order-rejected",                    "order.events"),
            Map.entry("event.order-delivered-for-payout",        "order.events"),
            Map.entry("notification.order-being-prepared",       "notifications"),
            Map.entry("notification.order-shipped",              "notifications"),
            Map.entry("notification.order-delivered",            "notifications"),
            Map.entry("notification.payment-failed",             "notifications"),
            Map.entry("notification.order-rejected",             "notifications"),
            Map.entry("notification.return-request-to-merchant", "notifications"),
            Map.entry("notification.return-confirmation-to-buyer","notifications")
    );

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void relay() {
        List<OrderOutbox> batch = outboxRepo.findUnpublishedWithLock(100);
        if (batch.isEmpty()) return;

        List<String> published = batch.stream()
                .filter(this::tryPublish)
                .map(OrderOutbox::getId)
                .toList();

        if (!published.isEmpty()) {
            outboxRepo.markPublished(published);
            log.debug("Order outbox relay: published {} events/commands", published.size());
        }
    }

    private boolean tryPublish(OrderOutbox event) {
        String topic = TOPIC_MAP.getOrDefault(event.getEventType(), "order.events");
        try {
            // kafkaTemplate has observationEnabled=true → automatically injects
            // traceparent header into the Kafka message. No extra code needed.
            kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload()).get();
            return true;
        } catch (Exception e) {
            log.error("Failed to publish outbox event id={} type={} topic={}: {}",
                    event.getId(), event.getEventType(), topic, e.getMessage());
            return false;
        }
    }
}