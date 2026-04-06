package io.tradeflow.catalog.kafka;

import io.micrometer.tracing.Tracer;
import io.tradeflow.catalog.entity.ProductDetail;
import io.tradeflow.catalog.repository.ProductDetailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * ProductEventConsumer — writes product events from Kafka into MongoDB.
 *
 * TRACING NOTES:
 * - The kafkaListenerContainerFactory (TracingConfig.java) has setObservationEnabled(true).
 * - This means when a message arrives, the W3C "traceparent" header is extracted
 *   and the current span CONTINUES the producer's trace (same traceId).
 * - MongoDB operations performed inside this method are auto-instrumented
 *   and will appear as child spans of this consumer span.
 * - We just add business tags to make the span searchable in Zipkin.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProductEventConsumer {

    private final ProductDetailRepository detailRepo;
    private final Tracer tracer; // ✅ injected to tag the current span

    @KafkaListener(
            topics = "catalog.product-events",
            groupId = "catalog-mongodb-writer",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeProductEvent(ConsumerRecord<String, Map<String, Object>> record, Acknowledgment ack) {
        Map<String, Object> payload = record.value();

        if (payload == null) {
            log.error("Received null payload");
            ack.acknowledge();
            return;
        }

        String eventType = (String) payload.get("event_type");
        String productId = (String) payload.get("product_id");

        // ✅ Tag the current span with business context
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("messaging.system",      "kafka");
            span.tag("messaging.destination", "catalog.product-events");
            span.tag("messaging.operation",   "mongodb-write");
            span.tag("catalog.event_type",    eventType != null ? eventType : "unknown");
            span.tag("catalog.product_id",    productId != null ? productId : "unknown");
        }

        log.info("[traceId={}] Received product event: type={}, productId={}",
                span != null ? span.context().traceId() : "none", eventType, productId);

        try {
            if (eventType == null) {
                log.error("Event type is null for productId: {}", productId);
                ack.acknowledge();
                return;
            }

            switch (eventType) {
                case "product.created"       -> handleProductCreated(payload);
                case "product.updated"       -> handleProductUpdated(payload);
                case "product.price.changed" -> log.debug("Price changed for product: {}", productId);
                case "product.delisted"      -> log.debug("Product delisted: {}", productId);
                default                      -> log.warn("Unknown event type: {}", eventType);
            }
            ack.acknowledge();

        } catch (Exception e) {
            if (span != null) span.error(e); // ✅ record the exception on the span
            log.error("Error processing product event: {}", e.getMessage(), e);
            // No ack — message will be retried
        }
    }

    private void handleProductCreated(Map<String, Object> payload) {
        String productId  = (String) payload.get("product_id");
        String merchantId = (String) payload.get("merchant_id");
        String title      = (String) payload.get("title");
        String categoryId = (String) payload.get("category_id");

        if (productId == null || merchantId == null || title == null || categoryId == null) {
            log.error("Missing required fields in product.created event: {}", payload);
            return;
        }

        if (detailRepo.findByProductId(productId).isPresent()) {
            log.debug("Product already exists in MongoDB: {}", productId);
            return;
        }

        // MongoDB write — auto-instrumented, appears as child span in Zipkin
        ProductDetail detail = ProductDetail.builder()
                .productId(productId)
                .merchantId(merchantId)
                .title(title)
                .categoryId(categoryId)
                .description("")
                .attributes(new HashMap<>())
                .mediaUrls(new java.util.ArrayList<>())
                .tags(new java.util.ArrayList<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        detailRepo.save(detail);
        log.info("✅ Product details written to MongoDB: productId={}, title={}", productId, title);
    }

    private void handleProductUpdated(Map<String, Object> payload) {
        String productId = (String) payload.get("product_id");
        @SuppressWarnings("unchecked")
        Map<String, Object> changes = (Map<String, Object>) payload.get("changes");

        if (productId == null) return;

        // MongoDB find + save — auto-instrumented, both appear as child spans
        detailRepo.findByProductId(productId).ifPresent(detail -> {
            if (changes != null && changes.containsKey("title")) {
                detail.setTitle((String) changes.get("title"));
            }
            detail.setUpdatedAt(Instant.now());
            detailRepo.save(detail);
            log.debug("Updated product in MongoDB: {}", productId);
        });
    }
}