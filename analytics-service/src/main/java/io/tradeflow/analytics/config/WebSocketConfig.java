package io.tradeflow.analytics.config;

import com.mongodb.client.MongoClient;
import io.tradeflow.analytics.websocket.LiveAnalyticsWebSocketHandler;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.mongo.MongoLockProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Bundles several small configuration beans:
 * - AnalyticsSecurityHelper (@PreAuthorize helper)
 * - Redis config
 * - WebSocket config
 * - ShedLock config
 */


@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private LiveAnalyticsWebSocketHandler liveAnalyticsWebSocketHandler;

    @Value("${analytics.websocket.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(liveAnalyticsWebSocketHandler, "/analytics/live/{merchantId}")
                .setAllowedOrigins(allowedOrigins.split(","));
    }
}

