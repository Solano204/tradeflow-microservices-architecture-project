package io.tradeflow.auth.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.tradeflow.auth.config.TracingHelper;
import io.tradeflow.auth.entity.AuthUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletionStage;

/**
 * IdentityEventConsumer — auto-provisions auth_users from buyer.registered events.
 *
 * TRACING:
 * The Kafka TracingConsumerInterceptor (configured in application.properties) automatically
 * extracts the W3C traceparent header from the Kafka message and resumes the same traceId
 * that was started in the Identity Service when it produced the event.
 *
 * This means every span created inside this method (DB persist, etc.) will appear
 * in Zipkin as children of the ORIGINAL trace — you can follow the full path:
 *   Identity Service → Kafka → Auth Service (this consumer) → PostgreSQL
 *
 * @Blocking is REQUIRED: without it, OTel context is not propagated correctly
 * through reactive pipelines.
 */
@ApplicationScoped
public class IdentityEventConsumer {

    private static final Logger LOG = Logger.getLogger(IdentityEventConsumer.class);

    @Inject ObjectMapper objectMapper;
    @Inject
    TracingHelper tracingHelper;

    @Incoming("identity-buyer-events")
    @Blocking   // ← Required for correct OTel context propagation in reactive pipelines
    @Transactional
    public CompletionStage<Void> onBuyerEvent(Message<String> message) {

        // The interceptor already resumed the remote trace.
        // Tag the current span with business context so it's searchable in Zipkin.
        Span currentSpan = Span.current();
        currentSpan.setAttribute("messaging.system",      "kafka");
        currentSpan.setAttribute("messaging.destination", "identity.buyer-events");
        currentSpan.setAttribute("messaging.operation",   "receive");
        currentSpan.setAttribute("service.component",     "IdentityEventConsumer");

        try {
            String payloadJson = message.getPayload();
            JsonNode node = objectMapper.readTree(payloadJson);

            String eventType = node.has("event_type") ? node.get("event_type").asText() : "";
            currentSpan.setAttribute("event.type", eventType);

            // Only handle buyer.registered — ignore other events on this topic
            if (!"buyer.registered".equals(eventType)) {
                LOG.debugf("IdentityEventConsumer: ignoring event type '%s'", eventType);
                return message.ack();
            }

            String buyerId        = node.get("buyer_id").asText();
            String email          = node.get("email").asText();
            String hashedPassword = node.get("hashed_password").asText();

            currentSpan.setAttribute("buyer.id",    buyerId);
            currentSpan.setAttribute("buyer.email.domain", extractDomain(email));

            // Idempotency guard — safe if the event is replayed
            if (AuthUser.existsByEmail(email)) {
                LOG.infof("Auth user already exists for %s — skipping", email);
                currentSpan.setAttribute("provisioning.skipped", true);
                return message.ack();
            }

            // DB persist — automatically becomes a child span of this trace
            // because jdbc.tracing=true in application.properties
            AuthUser user = new AuthUser();
            user.id             = buyerId;
            user.email          = email;
            user.hashedPassword = hashedPassword;
            user.roles          = "BUYER";
            user.status         = "ACTIVE";
            user.mfaEnabled     = false;
            user.persist();

            currentSpan.setAttribute("provisioning.userId", buyerId);
            currentSpan.setAttribute("provisioning.role",   "BUYER");
            LOG.infof("Auth user provisioned from buyer.registered event: %s", buyerId);

            return message.ack();

        } catch (Exception e) {
            currentSpan.recordException(e);
            currentSpan.setStatus(StatusCode.ERROR, e.getMessage());
            LOG.errorf("Failed to process buyer.registered event: %s", e.getMessage());
            // nack → SmallRye Kafka retries the message
            return message.nack(e);
        }
    }

    private String extractDomain(String email) {
        if (email == null || !email.contains("@")) return "unknown";
        return email.substring(email.indexOf('@') + 1);
    }
}