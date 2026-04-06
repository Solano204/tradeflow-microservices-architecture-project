package io.tradeflow.search.kafka;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import io.tradeflow.search.client.InternalServiceClients;
import io.tradeflow.search.document.ProductIndexDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Search Index Synchronization Consumers.
 *
 * TOPIC NAMES - exactly as published by each service:
 *
 * Catalog Service publishes to:    "catalog.product-events"
 *   payload has "event_type" field: product.created | product.updated |
 *                                   product.price.changed | product.delisted
 *
 * Inventory Service publishes to:  "inventory.events"
 *   payload has "event_type" field: inventory.restocked | inventory.out-of-stock |
 *                                   inventory.low-stock | inventory.reserved |
 *                                   inventory.confirmed | inventory.released
 *
 * Order Service publishes notifications to: "notifications" topic
 *   (Search does NOT need this — it only syncs from catalog + inventory)
 *
 * Eventual consistency window: ~650ms - 1.6s
 *   500ms: outbox relay → Kafka
 *   100ms: this consumer + ES write
 *   50-1000ms: ES refresh_interval
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SearchIndexConsumers {

    private final ElasticsearchClient esClient;
    private final InternalServiceClients internalClients;

    @Value("${elasticsearch.index.alias:products}")
    private String indexAlias;

    private volatile Instant lastEventProcessed = Instant.now();

    // ─────────────────────────────────────────────────────────────────────────
    // catalog.product-events - handles product.created / product.updated /
    //                          product.price.changed / product.delisted
    //
    // Catalog Service OutboxRelayService routes all catalog events to this topic.
    // The event_type field inside the payload tells us what to do.
    // ─────────────────────────────────────────────────────────────────────────

    @KafkaListener(
            topics = "catalog.product-events",
            groupId = "search-indexer",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onCatalogEvent(
            ConsumerRecord<String, Map<String, Object>> record,
            Acknowledgment ack) {

        Map<String, Object> payload = record.value();
        if (payload == null) {
            ack.acknowledge();
            return;
        }

        // event_type is embedded in the payload by Catalog's OutboxRelayService
        String eventType = (String) payload.get("event_type");
        String productId = (String) payload.get("product_id");

        if (productId == null) {
            log.debug("catalog.product-events: no product_id in payload, skipping");
            ack.acknowledge();
            return;
        }

        try {
            switch (eventType != null ? eventType : "") {

                case "product.created" -> {
                    log.info("Indexing new product: {}", productId);
                    ProductIndexDocument doc = internalClients.fetchProductForIndexing(productId);
                    esClient.index(i -> i.index(indexAlias).id(productId).document(doc));
                    lastEventProcessed = Instant.now();
                    log.info("Indexed product: {} — {}", productId, doc.getTitle());
                }

                case "product.updated", "product.price.changed" -> {
                    // Partial update — only changed fields
                    Map<String, Object> updates = new java.util.LinkedHashMap<>();

                    if (payload.containsKey("title"))
                        updates.put("title", payload.get("title"));
                    if (payload.containsKey("price") || payload.containsKey("new_price")) {
                        Object price = payload.getOrDefault("new_price",
                                payload.get("price"));
                        if (price != null) updates.put("price",
                                ((Number) Double.parseDouble(price.toString())).doubleValue());
                    }
                    if (payload.containsKey("description"))
                        updates.put("description", payload.get("description"));
                    if (payload.containsKey("attributes"))
                        updates.put("attributes", payload.get("attributes"));

                    // status change → flip available
                    Object status = payload.get("status");
                    if (status != null)
                        updates.put("available", "ACTIVE".equals(status.toString()));

                    if (!updates.isEmpty()) {
                        esClient.update(u -> u
                                .index(indexAlias)
                                .id(productId)
                                .doc(updates)
                                .retryOnConflict(3), Map.class);
                        lastEventProcessed = Instant.now();
                        log.debug("Updated ES doc: productId={}, fields={}",
                                productId, updates.keySet());
                    }
                }

                case "product.delisted" -> {
                    DeleteResponse resp = esClient.delete(d ->
                            d.index(indexAlias).id(productId));
                    lastEventProcessed = Instant.now();
                    if (resp.result() ==
                            co.elastic.clients.elasticsearch._types.Result.NotFound) {
                        log.debug("Product not in index during delist: {}", productId);
                    } else {
                        log.info("Removed delisted product from index: {}", productId);
                    }
                }

                default ->
                        log.debug("catalog.product-events: ignoring event_type='{}' for productId={}",
                                eventType, productId);
            }

            ack.acknowledge();

        } catch (Exception e) {
            log.error("Failed processing catalog event type={} productId={}: {}",
                    eventType, productId, e.getMessage(), e);
            // Do NOT ack — Kafka will retry
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // inventory.events - handles inventory.restocked and inventory.out-of-stock
    //
    // Inventory Service InventoryOutboxRepository → OutboxRelayService →
    //   publishes ALL inventory events to "inventory.events" topic.
    // We only care about availability changes for search.
    // ─────────────────────────────────────────────────────────────────────────

    @KafkaListener(
            topics = "inventory.events",
            groupId = "search-indexer",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onInventoryEvent(
            ConsumerRecord<String, Map<String, Object>> record,
            Acknowledgment ack) {

        Map<String, Object> payload = record.value();
        if (payload == null) {
            ack.acknowledge();
            return;
        }

        String eventType = (String) payload.get("event_type");
        String productId = (String) payload.get("product_id");

        if (productId == null) {
            ack.acknowledge();
            return;
        }

        try {
            switch (eventType != null ? eventType : "") {

                case "inventory.restocked" -> {
                    // Product back in stock → flip available: true
                    esClient.update(u -> u
                            .index(indexAlias)
                            .id(productId)
                            .doc(Map.of("available", true)), Map.class);
                    lastEventProcessed = Instant.now();
                    log.info("ES availability updated (restocked): productId={}", productId);
                }

                case "inventory.out-of-stock" -> {
                    // Product sold out → flip available: false
                    esClient.update(u -> u
                            .index(indexAlias)
                            .id(productId)
                            .doc(Map.of("available", false)), Map.class);
                    lastEventProcessed = Instant.now();
                    log.info("ES availability updated (out-of-stock): productId={}", productId);
                }

                // inventory.reserved / inventory.confirmed / inventory.released /
                // inventory.low-stock / inventory.initialized
                // → Search doesn't need these, safe to ack and ignore
                default ->
                        log.debug("inventory.events: ignoring event_type='{}' for productId={}",
                                eventType, productId);
            }

            ack.acknowledge();

        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            // 404 = product not yet in index, safe to ack (will be indexed via product.created)
            if (e.response() != null && e.response().status() == 404) {
                log.debug("inventory event for product not yet in ES index, skipping: {}",
                        productId);
                ack.acknowledge();
            } else {
                log.error("ES error updating inventory event productId={}: {}",
                        productId, e.getMessage());
                // Don't ack — retry
            }
        } catch (Exception e) {
            log.error("Failed processing inventory event type={} productId={}: {}",
                    eventType, productId, e.getMessage(), e);
        }
    }

    public Instant getLastEventProcessed() {
        return lastEventProcessed;
    }
}