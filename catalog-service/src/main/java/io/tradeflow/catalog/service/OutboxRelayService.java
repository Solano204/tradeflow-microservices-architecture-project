package io.tradeflow.catalog.service;

import io.tradeflow.catalog.entity.CatalogOutbox;
import io.tradeflow.catalog.repository.CatalogOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// ─────────────────────────────────────────────────────────────────────────────
// OUTBOX RELAY — publishes catalog events to Kafka
// SELECT FOR UPDATE SKIP LOCKED: multi-pod safe
// ─────────────────────────────────────────────────────────────────────────────

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxRelayService {

    private final CatalogOutboxRepository outboxRepo;
    private final KafkaTemplate<String, Map<String, Object>> kafkaTemplate;

    private static final Map<String, String> TOPIC_MAP = Map.of(
            "product.created",       "catalog.product-events",
            "product.updated",       "catalog.product-events",
            "product.price.changed", "catalog.product-events",
            "product.delisted",      "catalog.product-events"
    );

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void relay() {
        List<CatalogOutbox> batch = outboxRepo.findUnpublishedWithLock(100);
        if (batch.isEmpty()) return;

        List<String> published = batch.stream()
                .filter(this::tryPublish)
                .map(CatalogOutbox::getId)
                .toList();

        if (!published.isEmpty()) {
            outboxRepo.markPublished(published);
            log.debug("Catalog outbox relay: published {} events", published.size());
        }
    }

    private boolean tryPublish(CatalogOutbox event) {
        String topic = TOPIC_MAP.getOrDefault(event.getEventType(), "catalog.events");
        try {
            // Crear un nuevo mapa que incluya event_type
            Map<String, Object> enrichedPayload = new HashMap<>(event.getPayload());

            // Asegurar que event_type está presente en el payload
            if (!enrichedPayload.containsKey("event_type")) {
                enrichedPayload.put("event_type", event.getEventType());
            }

            kafkaTemplate.send(topic, event.getAggregateId(), enrichedPayload).get();
            return true;
        } catch (Exception e) {
            log.error("Failed to publish outbox event id={} type={}: {}",
                    event.getId(), event.getEventType(), e.getMessage());
            return false;
        }

    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MEDIA UPLOAD SERVICE — S3 + CDN URL generation
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// EXCEPTIONS
// ─────────────────────────────────────────────────────────────────────────────

