package io.tradeflow.inventory.service;

import io.tradeflow.inventory.entity.InventoryOutbox;
import io.tradeflow.inventory.repository.InventoryOutboxRepository;
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

    private final InventoryOutboxRepository outboxRepo;
    private final KafkaTemplate<String, Map<String, Object>> kafkaTemplate;

    private static final Map<String, String> TOPIC_MAP = Map.of(
            "inventory.initialized",  "inventory.events",
            "inventory.restocked",    "inventory.events",
            "inventory.reserved",     "inventory.events",
            "inventory.confirmed",    "inventory.events",
            "inventory.released",     "inventory.events",
            "inventory.low-stock",    "inventory.events",
            "inventory.out-of-stock", "inventory.events"
    );

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void relay() {
        List<InventoryOutbox> batch = outboxRepo.findUnpublishedWithLock(100);
        if (batch.isEmpty()) return;

        List<String> published = batch.stream()
                .filter(this::tryPublish)
                .map(InventoryOutbox::getId)
                .toList();

        if (!published.isEmpty()) {
            outboxRepo.markPublished(published);
            log.debug("Inventory outbox relay: published {} events", published.size());
        }
    }

    private boolean tryPublish(InventoryOutbox event) {
        String topic = TOPIC_MAP.getOrDefault(event.getEventType(), "inventory.events");
        try {
            kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload()).get();
            return true;
        } catch (Exception e) {
            log.error("Failed to publish outbox event id={} type={}: {}",
                    event.getId(), event.getEventType(), e.getMessage());
            return false;
        }
    }
}
