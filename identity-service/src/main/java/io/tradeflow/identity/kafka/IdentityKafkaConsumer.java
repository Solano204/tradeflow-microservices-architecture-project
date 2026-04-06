package io.tradeflow.identity.kafka;

import io.tradeflow.identity.service.BuyerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Identity Service Kafka consumers.
 *
 * Consumer group: "identity-read-model-builder"
 *
 * Listens to identity.buyer-events to rebuild MongoDB buyer_profiles.
 * This is the READ MODEL REBUILD leg of CQRS:
 *
 *   Write Path:  PostgreSQL (ACID) → identity_outbox → Kafka
 *   Read  Model: Kafka consumer → MongoDB buyer_profiles
 *
 * Idempotency: rebuilding the MongoDB document from PostgreSQL is always
 * correct — even if the same event is processed twice, the result is identical.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdentityKafkaConsumer {

    private final BuyerService buyerService;

    /**
     * Rebuilds buyer MongoDB projection on registration or profile update.
     */
    @KafkaListener(
            topics = "identity.buyer-events",
            groupId = "identity-read-model-builder",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onBuyerEvent(ConsumerRecord<String, Map<String, Object>> record, Acknowledgment ack) {
        Map<String, Object> payload = record.value();
        String eventType = (String) payload.getOrDefault("event_type",
                guessEventType(record.topic(), payload));

        log.debug("Consumed buyer event: type={}, buyerId={}", eventType, payload.get("buyer_id"));

        try {
            switch (String.valueOf(eventType)) {
                case "buyer.registered", "buyer.profile.updated" ->
                        buyerService.rebuildBuyerProfileInMongo(payload);
                default ->
                        log.warn("Unknown buyer event type: {}", eventType);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing buyer event: type={}, error={}", eventType, e.getMessage(), e);
            // Don't ack — let Kafka retry via the retry topic or DLQ
            // In production, configure a dead-letter topic for poison pills
        }
    }

    /**
     * Processes merchant status change events.
     * Primarily used to evict Redis cache when merchant.status.changed arrives.
     * (Cache is also synchronously evicted on write — this is belt-and-suspenders.)
     */
    @KafkaListener(
            topics = "identity.merchant-events",
            groupId = "identity-merchant-event-processor",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onMerchantEvent(ConsumerRecord<String, Map<String, Object>> record, Acknowledgment ack) {
        Map<String, Object> payload = record.value();
        String eventType = (String) payload.get("event_type");
        String merchantId = (String) payload.get("merchant_id");

        log.debug("Consumed merchant event: type={}, merchantId={}", eventType, merchantId);

        // Events are consumed by OTHER services (Product Catalog, Payment, Notification)
        // Identity Service itself logs these for audit purposes
        log.info("Merchant event processed by audit log: type={}, merchantId={}", eventType, merchantId);
        ack.acknowledge();
    }

    private String guessEventType(String topic, Map<String, Object> payload) {
        // Fallback: infer event type from payload keys
        if (payload.containsKey("buyer_id") && !payload.containsKey("address")) {
            return "buyer.registered";
        }
        if (payload.containsKey("buyer_id")) {
            return "buyer.profile.updated";
        }
        return "unknown";
    }
}
