package io.tradeflow.analytics.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tradeflow.analytics.dto.AnalyticsDtos.LiveSalesEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Redis Pub/Sub bridge for multi-pod live analytics WebSocket routing.
 *
 * Problem: Pod B processes payment event for merchant mrc_a3f8.
 *          Pod A holds the WebSocket connection for mrc_a3f8.
 *          Pod B cannot push to Pod A's session directly.
 *
 * Solution: Pod B publishes to Redis "ws:analytics:mrc_a3f8"
 *           Pod A (subscribed to the pattern) receives the message
 *           Pod A pushes to its local WebSocket session
 *
 * Channel pattern: "ws:analytics:*"
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LiveAnalyticsPubSubPublisher implements MessageListener {

    private static final String CHANNEL_PREFIX = "ws:analytics:";

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final LiveAnalyticsWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void subscribeToLiveAnalyticsChannel() {
        listenerContainer.addMessageListener(this, new PatternTopic(CHANNEL_PREFIX + "*"));
        log.info("Live analytics publisher subscribed to Redis pattern: {}*", CHANNEL_PREFIX);
    }

    /**
     * Publish a live sales event to Redis — routed to whichever pod holds the session.
     */
    public void publishLiveSalesEvent(String merchantId, LiveSalesEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            stringRedisTemplate.convertAndSend(CHANNEL_PREFIX + merchantId, payload);
        } catch (Exception e) {
            log.error("Failed to publish live sales event for merchantId={}: {}", merchantId, e.getMessage());
        }
    }

    /**
     * Receive Redis messages and route to local WebSocket sessions.
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String channel = new String(message.getChannel());
            String merchantId = channel.substring(CHANNEL_PREFIX.length());

            if (!webSocketHandler.isConnected(merchantId)) return;

            LiveSalesEvent event = objectMapper.readValue(
                message.getBody(), LiveSalesEvent.class);
            webSocketHandler.pushToMerchant(merchantId, event);

        } catch (Exception e) {
            log.error("Error processing live analytics Redis message: {}", e.getMessage());
        }
    }
}
