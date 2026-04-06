package io.tradeflow.identity.kafka;

import io.tradeflow.identity.entity.IdentityOutbox;
import io.tradeflow.identity.repository.IdentityOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes authentication-related events to be consumed by Auth Service.
 * These events are written to the outbox table and will be published to Kafka
 * by the existing OutboxRelayService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthEventPublisher {

    private final IdentityOutboxRepository outboxRepository;

    /**
     * Publish an event requesting Auth Service to create a new user.
     * Called after a buyer registers or merchant onboards.
     */
    @Transactional
    public void publishUserCreationRequest(String userId, String email, String password, List<String> roles) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event_type", "auth.user.creation.requested");
        payload.put("userId", userId);
        payload.put("email", email);
        payload.put("password", password);
        payload.put("roles", roles);
        payload.put("timestamp", Instant.now().toString());

        IdentityOutbox outbox = IdentityOutbox.builder()
                .eventType("auth.user.creation.requested")
                .payload(payload)
                .aggregateId(userId)
                .build();

        outboxRepository.save(outbox);
        log.info("Published auth.user.creation.requested for user: {}", email);
    }

    /**
     * Publish an event requesting Auth Service to reset a user's password.
     */
    @Transactional
    public void publishPasswordResetRequest(String userId, String email, String newPassword) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event_type", "auth.user.password.reset");
        payload.put("userId", userId);
        payload.put("email", email);
        payload.put("newPassword", newPassword);
        payload.put("timestamp", Instant.now().toString());
        payload.put("requestId", UUID.randomUUID().toString());

        IdentityOutbox outbox = IdentityOutbox.builder()
                .eventType("auth.user.password.reset")
                .payload(payload)
                .aggregateId(userId)
                .build();

        outboxRepository.save(outbox);
        log.info("Published auth.user.password.reset for user: {}", email);
    }

    /**
     * Publish an event to update a user's status in Auth Service.
     * Called when a user is suspended, activated, or terminated.
     */
    @Transactional
    public void publishUserStatusChanged(String userId, String email, String status) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event_type", "auth.user.status.changed");
        payload.put("userId", userId);
        payload.put("email", email);
        payload.put("status", status);
        payload.put("timestamp", Instant.now().toString());

        IdentityOutbox outbox = IdentityOutbox.builder()
                .eventType("auth.user.status.changed")
                .payload(payload)
                .aggregateId(userId)
                .build();

        outboxRepository.save(outbox);
        log.info("Published auth.user.status.changed for user: {} -> {}", email, status);
    }
}