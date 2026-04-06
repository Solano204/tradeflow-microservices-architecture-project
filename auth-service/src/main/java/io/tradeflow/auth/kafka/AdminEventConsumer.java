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
 * AdminEventConsumer — auto-provisions ADMIN auth_users from admin.registered events.
 *
 * TRACING: Same strategy as IdentityEventConsumer and MerchantEventConsumer.
 */
@ApplicationScoped
public class AdminEventConsumer {

    private static final Logger LOG = Logger.getLogger(AdminEventConsumer.class);

    @Inject ObjectMapper objectMapper;
    @Inject
    TracingHelper tracingHelper;

    @Incoming("identity-admin-events")
    @Blocking   // ← Required for correct OTel context propagation in reactive pipelines
    @Transactional
    public CompletionStage<Void> onAdminEvent(Message<String> message) {

        Span currentSpan = Span.current();
        currentSpan.setAttribute("messaging.system",      "kafka");
        currentSpan.setAttribute("messaging.destination", "identity-admin-events");
        currentSpan.setAttribute("messaging.operation",   "receive");
        currentSpan.setAttribute("service.component",     "AdminEventConsumer");

        try {
            String payloadJson = message.getPayload();
            JsonNode node = objectMapper.readTree(payloadJson);

            String eventType = node.has("event_type") ? node.get("event_type").asText() : "";
            currentSpan.setAttribute("event.type", eventType);

            if (!"admin.registered".equals(eventType)) {
                LOG.debugf("AdminEventConsumer: ignoring event type '%s'", eventType);
                return message.ack();
            }

            String adminId        = node.get("admin_id").asText();
            String email          = node.get("email").asText();
            String hashedPassword = node.get("hashed_password").asText();
            String department     = node.has("department") ? node.get("department").asText() : "GENERAL";

            currentSpan.setAttribute("admin.id",         adminId);
            currentSpan.setAttribute("admin.department", department);
            currentSpan.setAttribute("admin.email.domain", extractDomain(email));

            // Idempotency guard
            if (AuthUser.existsByEmail(email)) {
                LOG.infof("Auth user already exists for %s — skipping", email);
                currentSpan.setAttribute("provisioning.skipped", true);
                return message.ack();
            }

            AuthUser user = new AuthUser();
            user.id             = adminId;
            user.email          = email;
            user.hashedPassword = hashedPassword;
            user.roles          = "ADMIN";
            user.status         = "ACTIVE";
            user.mfaEnabled     = false;
            user.persist();

            currentSpan.setAttribute("provisioning.userId", adminId);
            currentSpan.setAttribute("provisioning.role",   "ADMIN");
            LOG.infof("Auth ADMIN user provisioned: %s (%s, dept: %s)", adminId, email, department);

            return message.ack();

        } catch (Exception e) {
            currentSpan.recordException(e);
            currentSpan.setStatus(StatusCode.ERROR, e.getMessage());
            LOG.errorf("Failed to process admin.registered event: %s", e.getMessage());
            return message.nack(e);
        }
    }

    private String extractDomain(String email) {
        if (email == null || !email.contains("@")) return "unknown";
        return email.substring(email.indexOf('@') + 1);
    }
}