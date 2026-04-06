package io.tradeflow.analytics.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tradeflow.analytics.dto.AnalyticsDtos.LiveSalesEvent;
import io.tradeflow.analytics.dto.AnalyticsDtos.MerchantSummaryResponse;
import io.tradeflow.analytics.dto.KafkaEventDtos.PaymentProcessedEvent;
import io.tradeflow.analytics.service.AnalyticsQueryService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Two responsibilities:
 *
 * 1. KAFKA LISTENER: Consumes payment.processed events for LIVE PUSH
 *    (separate from the Kafka Streams topology — this is for real-time WebSocket push only)
 *    When a payment arrives: check if merchant has an open WebSocket → push live event
 *
 * 2. REDIS PUB/SUB: Bridge for multi-pod WebSocket routing
 *    If merchant's WebSocket is on Pod A but payment was processed by Pod B:
 *    Pod B publishes to Redis → Pod A receives → pushes to merchant's session
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LiveAnalyticsWebSocketPublisher implements MessageListener {

    private static final String WS_CHANNEL_PREFIX = "ws:analytics:";

    private final LiveAnalyticsWebSocketHandler webSocketHandler;
    private final AnalyticsQueryService queryService;
    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void subscribeToLiveChannel() {
        listenerContainer.addMessageListener(this, new PatternTopic(WS_CHANNEL_PREFIX + "*"));
        log.info("Live analytics publisher subscribed to Redis pattern: {}*", WS_CHANNEL_PREFIX);
    }

    /**
     * Kafka listener for payment.processed — for LIVE DASHBOARD PUSH only.
     * The Kafka Streams topology handles the aggregation independently.
     */
    @KafkaListener(topics = "payment.processed", groupId = "analytics-service-live")
    public void onPaymentForLiveDashboard(PaymentProcessedEvent event, Acknowledgment ack) {
        try {
            if (event == null || !"SUCCESS".equals(event.getStatus())) {
                ack.acknowledge();
                return;
            }

            String merchantId = event.getMerchantId();
            if (merchantId == null) {
                ack.acknowledge();
                return;
            }

            String primaryProduct = (event.getItems() != null && !event.getItems().isEmpty())
                ? event.getItems().get(0).getTitle()
                : "a product";

            LiveSalesEvent liveEvent = LiveSalesEvent.builder()
                .type("SALE")
                .orderId(event.getOrderId())
                .amount(event.getAmount() != null ? event.getAmount().doubleValue() : 0.0)
                .currency(event.getCurrency())
                .productTitle(primaryProduct)
                .buyerCity(event.getBuyerCity())   // anonymized city only — no PII
                .timestamp(event.getOccurredAt() != null ? event.getOccurredAt() : Instant.now())
                .build();

            // Publish to Redis — whichever pod holds the WebSocket session will receive this
            publishToRedis(merchantId, liveEvent);

        } catch (Exception e) {
            log.error("Error processing payment for live dashboard: {}", e.getMessage(), e);
        } finally {
            ack.acknowledge();
        }
    }

    /**
     * Send initial state when a merchant first connects.
     * Called from LiveAnalyticsWebSocketHandler.afterConnectionEstablished via a separate thread.
     */
    public void sendInitialState(String merchantId) {
        try {
            MerchantSummaryResponse summary = queryService.getMerchantSummary(merchantId);
            LiveSalesEvent initEvent = LiveSalesEvent.builder()
                .type("INITIAL_STATE")
                .timestamp(Instant.now())
                .currentSummary(summary)
                .build();
            webSocketHandler.pushToMerchant(merchantId, initEvent);
        } catch (Exception e) {
            log.warn("Failed to send initial state to merchantId={}: {}", merchantId, e.getMessage());
        }
    }

    /**
     * Redis Pub/Sub receiver — called on any pod that subscribes to the channel.
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String channel = new String(message.getChannel());
            String merchantId = channel.substring(WS_CHANNEL_PREFIX.length());

            if (!webSocketHandler.isConnected(merchantId)) return;

            LiveSalesEvent event = objectMapper.readValue(message.getBody(), LiveSalesEvent.class);
            webSocketHandler.pushToMerchant(merchantId, event);

        } catch (Exception e) {
            log.error("Failed to process Redis Pub/Sub message for live analytics: {}", e.getMessage());
        }
    }

    // ===== Private =====

    private void publishToRedis(String merchantId, LiveSalesEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(WS_CHANNEL_PREFIX + merchantId, payload);
        } catch (Exception e) {
            log.error("Failed to publish live event to Redis for merchantId={}", merchantId, e);
        }
    }
}
