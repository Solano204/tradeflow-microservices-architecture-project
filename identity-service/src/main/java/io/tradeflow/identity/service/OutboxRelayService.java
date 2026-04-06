package io.tradeflow.identity.service;

import io.tradeflow.identity.entity.IdentityOutbox;
import io.tradeflow.identity.repository.IdentityOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxRelayService {

    private final IdentityOutboxRepository outboxRepository;
    private final KafkaTemplate<String, Map<String, Object>> kafkaTemplate;

    private static final int BATCH_SIZE = 100;

    private static final Map<String, String> EVENT_TO_TOPIC = Map.ofEntries(
            // Buyer events
            Map.entry("buyer.registered", "identity.buyer-events"),
            Map.entry("buyer.profile.updated", "identity.buyer-events"),

            // Merchant events
            Map.entry("merchant.onboarding.started", "identity.merchant-events"),
            Map.entry("merchant.kyc.submitted", "identity.merchant-events"),
            Map.entry("merchant.status.changed", "identity.merchant-events"),
            Map.entry("merchant.terminated", "identity.merchant-events"),

            // ADMIN EVENTS
            Map.entry("admin.registered", "identity.admin-events"),
            Map.entry("admin.status.changed", "identity.admin-events"),

            // Auth service events
            Map.entry("auth.user.creation.requested", "auth-events"),
            Map.entry("auth.user.password.reset", "auth-events"),
            Map.entry("auth.user.status.changed", "auth-events")
    );

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void relay() {
        List<IdentityOutbox> batch = outboxRepository.findUnpublishedWithLock(BATCH_SIZE);
        if (batch.isEmpty()) return;

        log.debug("Outbox relay: found {} unpublished events", batch.size());

        List<String> publishedIds = batch.stream()
                .filter(this::tryPublish)
                .map(IdentityOutbox::getId)
                .toList();

        if (!publishedIds.isEmpty()) {
            outboxRepository.markPublished(publishedIds);
            log.info("Outbox relay: published {} events: {}",
                    publishedIds.size(),
                    batch.stream().map(e -> e.getEventType()).toList());
        }
    }

    private boolean tryPublish(IdentityOutbox event) {
        String topic = EVENT_TO_TOPIC.get(event.getEventType());

        // Si no hay mapping específico, usar un default
        if (topic == null) {
            topic = "identity.events";
            log.warn("No topic mapping for event type: {}, using default", event.getEventType());
        }

        try {
            log.debug("Publishing event {} to topic {}", event.getEventType(), topic);
            kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload())
                    .get(); // wait for ack
            return true;
        } catch (Exception e) {
            log.error("Failed to publish outbox event id={} type={} to topic={}: {}",
                    event.getId(), event.getEventType(), topic, e.getMessage());
            return false;
        }
    }
}