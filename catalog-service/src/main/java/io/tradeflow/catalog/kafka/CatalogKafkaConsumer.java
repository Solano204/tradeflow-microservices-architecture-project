package io.tradeflow.catalog.kafka;

import io.micrometer.tracing.Tracer;
import io.tradeflow.catalog.service.CatalogRedisService;
import io.tradeflow.catalog.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * CatalogKafkaConsumer — receives merchant and product events.
 *
 * TRACING NOTES:
 * - The traceId is automatically extracted from the Kafka message headers by
 *   the kafkaListenerContainerFactory (configured in TracingConfig.java with
 *   setObservationEnabled(true)).
 * - You do NOT need to do anything special here to continue the trace —
 *   it's already active when your @KafkaListener method is called.
 * - We inject Tracer only to add BUSINESS TAGS to the current span so you
 *   can search in Zipkin by merchantId, eventType, etc.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CatalogKafkaConsumer {

    private final ProductService productService;
    private final CatalogRedisService redisService;
    private final Tracer tracer; // ✅ injected to tag the current span

    /**
     * Merchant lifecycle events from Identity Service.
     * The traceId that started in Identity Service continues here automatically.
     */
    @KafkaListener(
            topics = "identity.merchant-events",
            groupId = "catalog-merchant-event-handler",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onMerchantEvent(ConsumerRecord<String, Map<String, Object>> record, Acknowledgment ack) {
        Map<String, Object> payload = record.value();
        String eventType = (String) payload.get("event_type");
        String merchantId = (String) payload.get("merchant_id");
        String status     = (String) payload.get("status");

        // ✅ Tag the current span with business context — visible in Zipkin
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("messaging.system",      "kafka");
            span.tag("messaging.destination", "identity.merchant-events");
            span.tag("messaging.operation",   "receive");
            span.tag("catalog.event_type",    eventType != null ? eventType : "unknown");
            span.tag("catalog.merchant_id",   merchantId != null ? merchantId : "unknown");
        }

        log.info("[traceId={}] Merchant event received: type={}, merchantId={}, status={}",
                span != null ? span.context().traceId() : "none", eventType, merchantId, status);

        try {
            if ("merchant.status.changed".equals(eventType) && "SUSPENDED".equals(status)) {
                productService.handleMerchantSuspended(merchantId);
            } else if ("merchant.terminated".equals(eventType)) {
                productService.handleMerchantTerminated(merchantId);
            }
            ack.acknowledge();
        } catch (Exception e) {
            if (span != null) span.error(e); // ✅ record exception on the span
            log.error("Error handling merchant event: {}", e.getMessage(), e);
        }
    }

    /**
     * Cache invalidation consumer — listens for product events published by THIS service
     * (other pods in the cluster, or other services that emit to catalog.product-events).
     * The traceId is extracted from headers so you can trace which upstream operation
     * triggered this cache eviction.
     */
    @KafkaListener(
            topics = "catalog.product-events",
            groupId = "catalog-cache-invalidator",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onProductEvent(ConsumerRecord<String, Map<String, Object>> record, Acknowledgment ack) {
        Map<String, Object> payload = record.value();
        String productId = (String) payload.get("product_id");
        String eventType = (String) payload.get("event_type");

        // ✅ Tag the current span
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("messaging.system",      "kafka");
            span.tag("messaging.destination", "catalog.product-events");
            span.tag("messaging.operation",   "cache-invalidation");
            span.tag("catalog.product_id",    productId != null ? productId : "unknown");
            span.tag("catalog.event_type",    eventType != null ? eventType : "unknown");
        }

        if (productId != null) {
            redisService.evict(productId);
            log.debug("[traceId={}] Cache invalidated via Kafka: productId={}, event={}",
                    span != null ? span.context().traceId() : "none", productId, eventType);
        }
        ack.acknowledge();
    }
}