package io.tradeflow.inventory.kafka;

import io.tradeflow.inventory.dto.InventoryDtos;
import io.tradeflow.inventory.dto.InventoryDtos.CreateInventoryRequest;
import io.tradeflow.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Inventory Kafka Consumers
 *
 * product.created → auto-initialize inventory record.
 * When Catalog Service publishes product.created, Inventory Service creates
 * an inventory row with initial_qty from the event payload.
 * This is the primary trigger for Endpoint 1 in production.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryKafkaConsumer {

    private final InventoryService inventoryService;



    @KafkaListener(
            topics = "cmd.confirm-inventory",
            groupId = "inventory-confirm-handler",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onConfirmCommand(ConsumerRecord<String, Map<String, Object>> record, Acknowledgment ack) {
        Map<String, Object> payload = record.value();
        String orderId       = (String) payload.get("order_id");
        String reservationId = (String) payload.get("reservation_id");
        String idemKey       = (String) payload.get("idempotency_key");

        if (orderId == null || reservationId == null) {
            log.warn("cmd.confirm-inventory: missing orderId or reservationId — acking and skipping");
            ack.acknowledge();
            return;
        }

        log.info("cmd.confirm-inventory received: orderId={}, reservationId={}", orderId, reservationId);

        try {
            InventoryDtos.ConfirmRequest req = new InventoryDtos.ConfirmRequest(reservationId, orderId, idemKey);
            inventoryService.confirm(orderId, req);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Confirm failed: orderId={}, error={}", orderId, e.getMessage(), e);
            // Don't ack — let Kafka retry
        }
    }



    /**
     * product.created — initialize inventory for a new product.
     *
     * Catalog Service publishes this when a product transitions DRAFT → ACTIVE.
     * The payload contains product_id, merchant_id, and initial stock info.
     *
     * Idempotent: if inventory already exists for the product_id, the service
     * returns ConflictException which we catch and treat as "already handled."
     */
    @KafkaListener(
            topics = "cmd.reserve-inventory",
            groupId = "inventory-reserve-handler",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onReserveCommand(ConsumerRecord<String, Map<String, Object>> record, Acknowledgment ack) {
        Map<String, Object> payload = record.value();
        String orderId    = (String) payload.get("order_id");
        String productId  = (String) payload.get("product_id");
        String buyerId    = (String) payload.get("buyer_id");
        int qty           = ((Number) payload.getOrDefault("qty", 1)).intValue();
        String idemKey    = (String) payload.get("idempotency_key");



        log.info("cmd.reserve-inventory received: orderId={}, productId={}, qty={}", orderId, productId, qty);

        try {
            InventoryDtos.ReserveRequest req = new InventoryDtos.ReserveRequest(orderId, buyerId, qty, idemKey);
            inventoryService.reserve(productId, req);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Reserve failed: orderId={}, error={}", orderId, e.getMessage(), e);
            // Don't ack — Kafka will retry
        }
    }


    @KafkaListener(
            topics = "catalog.product-events",
            groupId = "inventory-product-event-handler",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onCatalogEvent(ConsumerRecord<String, Map<String, Object>> record, Acknowledgment ack) {
        Map<String, Object> payload = record.value();
        String eventType = (String) payload.get("event_type");

        if (!"product.created".equals(eventType)) {
            ack.acknowledge();
            return;
        }

        String productId  = (String) payload.get("product_id");
        String merchantId = (String) payload.get("merchant_id");

        log.info("product.created received: productId={}", productId);

        try {
            // Initial qty from event; default to 0 if not provided
            // (merchant will use PUT /inventory/{productId}/stock to add actual stock)
            int initialQty = payload.get("initial_qty") instanceof Number n
                    ? n.intValue() : 0;
            String sku = (String) payload.get("sku");

            CreateInventoryRequest req = new CreateInventoryRequest(
                    productId, merchantId, sku, initialQty, 10, 10);
            inventoryService.createInventory(req);

            log.info("Inventory initialized from product.created: productId={}, qty={}", productId, initialQty);
            ack.acknowledge();

        } catch (Exception e) {
            // ConflictException = already initialized (duplicate event delivery) — safe to ack
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                log.debug("Inventory already exists for productId={} (duplicate event)", productId);
                ack.acknowledge();
            } else {
                log.error("Failed to initialize inventory for productId={}: {}", productId, e.getMessage(), e);
                // Don't ack — let Kafka retry
            }
        }
    }
}
