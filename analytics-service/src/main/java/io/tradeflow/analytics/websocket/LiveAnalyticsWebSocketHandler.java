package io.tradeflow.analytics.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tradeflow.analytics.dto.AnalyticsDtos.LiveSalesEvent;
import io.tradeflow.analytics.dto.AnalyticsDtos.MerchantSummaryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler for the live merchant dashboard.
 *
 * Endpoint: WS /analytics/live/{merchantId}
 *
 * Lifecycle:
 * 1. Merchant's dashboard opens → WebSocket connects
 * 2. onOpen: sends current summary state immediately (no blank dashboard)
 * 3. payment.processed arrives → Redis Pub/Sub → LiveAnalyticsWebSocketPublisher
 *    → pushToMerchant() → merchant's dashboard updates in real-time
 * 4. Merchant closes dashboard → onClose → session removed
 *
 * Multi-pod: uses Redis Pub/Sub (see LiveAnalyticsWebSocketPublisher)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LiveAnalyticsWebSocketHandler extends TextWebSocketHandler {

    // merchantId → set of active WebSocket sessions on THIS pod
    private static final Map<String, Set<WebSocketSession>> activeSessions =
        new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String merchantId = extractMerchantId(session);
        if (merchantId == null) {
            log.warn("Live analytics WebSocket rejected: no merchantId in path");
            try { session.close(CloseStatus.POLICY_VIOLATION); } catch (IOException ignored) {}
            return;
        }

        session.getAttributes().put("merchantId", merchantId);
        activeSessions.computeIfAbsent(merchantId, k -> ConcurrentHashMap.newKeySet())
            .add(session);

        log.info("Merchant {} connected to live dashboard, sessionId={}", merchantId, session.getId());

        // Send current state immediately on connect — no blank dashboard
        // (The summary query is handled by LiveAnalyticsWebSocketPublisher.sendInitialState)
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String merchantId = (String) session.getAttributes().get("merchantId");
        if (merchantId != null) {
            Set<WebSocketSession> sessions = activeSessions.get(merchantId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    activeSessions.remove(merchantId);
                }
            }
        }
        log.debug("Live analytics WebSocket closed: merchantId={}, status={}",
            merchantId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Live analytics transport error: sessionId={}, error={}",
            session.getId(), exception.getMessage());
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
    }

    /**
     * Push a live sales event to all active sessions for a merchant ON THIS POD.
     */
    public void pushToMerchant(String merchantId, LiveSalesEvent event) {
        Set<WebSocketSession> sessions = activeSessions.getOrDefault(merchantId, Set.of());
        if (sessions.isEmpty()) return;

        try {
            String payload = objectMapper.writeValueAsString(event);
            TextMessage message = new TextMessage(payload);
            Set<WebSocketSession> dead = ConcurrentHashMap.newKeySet();

            for (WebSocketSession session : sessions) {
                try {
                    if (session.isOpen()) {
                        session.sendMessage(message);
                    } else {
                        dead.add(session);
                    }
                } catch (IOException e) {
                    log.warn("Failed push to session: {}", session.getId());
                    dead.add(session);
                }
            }
            if (!dead.isEmpty()) sessions.removeAll(dead);

        } catch (Exception e) {
            log.error("Failed to serialize live event for merchantId={}", merchantId, e);
        }
    }

    public boolean isConnected(String merchantId) {
        Set<WebSocketSession> sessions = activeSessions.get(merchantId);
        return sessions != null && !sessions.isEmpty();
    }

    public Set<String> getConnectedMerchants() {
        return activeSessions.keySet();
    }

    private String extractMerchantId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) return null;
        String path = uri.getPath();
        // Path: /analytics/live/{merchantId}
        String[] parts = path.split("/");
        return parts.length >= 4 ? parts[3] : null;
    }
}
