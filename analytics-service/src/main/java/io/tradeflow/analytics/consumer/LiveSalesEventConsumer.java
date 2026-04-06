package io.tradeflow.analytics.consumer;

import io.tradeflow.analytics.dto.AnalyticsDtos.LiveSalesEvent;
import io.tradeflow.analytics.websocket.LiveAnalyticsPubSubPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Kafka consumer for the live merchant dashboard WebSocket feed.
 *
 * Listens to "payment.events" topic.
 * Payment Service (Quarkus MockPaymentService) publishes with:
 *   - camelCase keys: orderId, merchantId, buyerId, stripeChargeId
 *   - snake_case keys: order_id, merchant_id, buyer_id, charge_id
 *   - Both are present for compatibility (see MockPaymentService.publishChargeEvent)
 *
 * Only processes event_type == "payment.processed" with status == "SUCCEEDED"
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LiveSalesEventConsumer {

    private final LiveAnalyticsPubSubPublisher publisher;

    @KafkaListener(
            topics = "payment.events",
            groupId = "analytics-live-feed",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onPaymentProcessed(
            ConsumerRecord<String, Object> record, Acknowledgment ack) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = (Map<String, Object>) record.value();
            if (event == null) { ack.acknowledge(); return; }

            // Only push for successful payments
            String eventType = getStr(event, "event_type");
            if (!"payment.processed".equals(eventType)) {
                ack.acknowledge();
                return;
            }

            String status = getStr(event, "status");
            if (status != null && !"SUCCEEDED".equals(status)) {
                ack.acknowledge();
                return;
            }

            // Handle both camelCase (Quarkus) and snake_case
            String merchantId = getStr(event, "merchant_id");
            if (merchantId == null) merchantId = getStr(event, "merchantId");
            if (merchantId == null || merchantId.isBlank()) {
                ack.acknowledge();
                return;
            }

            String orderId = getStr(event, "order_id");
            if (orderId == null) orderId = getStr(event, "orderId");

            double amount = getDouble(event, "amount");
            String currency = getStr(event, "currency", "MXN");
            String productTitle = extractFirstProductTitle(event);
            String buyerCity = getStr(event, "buyer_city", "");

            LiveSalesEvent liveEvent = LiveSalesEvent.builder()
                    .type("SALE")
                    .orderId(orderId)
                    .amount(amount)
                    .currency(currency)
                    .productTitle(productTitle)
                    .buyerCity(buyerCity)
                    .timestamp(Instant.now())
                    .build();

            publisher.publishLiveSalesEvent(merchantId, liveEvent);
            log.debug("Live sales event: merchantId={}, orderId={}, amount={}",
                    merchantId, orderId, amount);

            ack.acknowledge();

        } catch (Exception e) {
            log.error("Error processing payment for live feed: key={}, error={}",
                    record.key(), e.getMessage(), e);
            ack.acknowledge();
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String extractFirstProductTitle(Map<String, Object> event) {
        Object items = event.get("items");
        if (items instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> item) {
                Object title = ((Map<String, Object>) item).get("title");
                return title != null ? title.toString() : "producto";
            }
        }
        return "producto";
    }

    private String getStr(Map<String, Object> m, String key) {
        return getStr(m, key, null);
    }

    private String getStr(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v != null ? v.toString() : def;
    }

    private double getDouble(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return 0.0;
        try { return Double.parseDouble(v.toString()); }
        catch (Exception e) { return 0.0; }
    }
}