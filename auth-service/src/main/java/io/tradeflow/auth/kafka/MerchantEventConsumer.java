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
 * MerchantEventConsumer — auto-provisions auth_users from merchant.onboarding.started events.
 *
 * TRACING: Same strategy as IdentityEventConsumer.
 * The TracingConsumerInterceptor extracts the W3C traceparent header injected by the
 * Identity Service's producer interceptor, so the same traceId flows through:
 *   Identity Service → Kafka → Auth Service → PostgreSQL
 */
@ApplicationScoped
public class MerchantEventConsumer {

    private static final Logger LOG = Logger.getLogger(MerchantEventConsumer.class);

    @Inject ObjectMapper objectMapper;
    @Inject
    TracingHelper tracingHelper;

    @Incoming("identity-merchant-events")
    @Blocking   // ← Required for correct OTel context propagation in reactive pipelines
    @Transactional
    public CompletionStage<Void> onMerchantEvent(Message<String> message) {

        Span currentSpan = Span.current();
        currentSpan.setAttribute("messaging.system",      "kafka");
        currentSpan.setAttribute("messaging.destination", "identity-merchant-events");
        currentSpan.setAttribute("messaging.operation",   "receive");
        currentSpan.setAttribute("service.component",     "MerchantEventConsumer");

        try {
            String payloadJson = message.getPayload();
            JsonNode node = objectMapper.readTree(payloadJson);

            String eventType = node.has("event_type") ? node.get("event_type").asText() : "";
            currentSpan.setAttribute("event.type", eventType);

            if (!"merchant.onboarding.started".equals(eventType)) {
                LOG.debugf("MerchantEventConsumer: ignoring event type '%s'", eventType);
                return message.ack();
            }

            String merchantId     = node.get("merchant_id").asText();
            String email          = node.get("contact_email").asText();
            String hashedPassword = node.has("hashed_password")
                    ? node.get("hashed_password").asText()
                    : null;

            currentSpan.setAttribute("merchant.id", merchantId);
            currentSpan.setAttribute("merchant.email.domain", extractDomain(email));

            // Idempotency guard
            if (AuthUser.existsByEmail(email)) {
                LOG.infof("Auth user already exists for %s — skipping", email);
                currentSpan.setAttribute("provisioning.skipped", true);
                return message.ack();
            }

            AuthUser user = new AuthUser();
            user.id             = merchantId;
            user.email          = email;
            user.hashedPassword = hashedPassword;
            user.roles          = "MERCHANT";
            user.merchantId     = merchantId;
            user.status         = "ACTIVE";
            user.mfaEnabled     = false;
            user.persist();

            currentSpan.setAttribute("provisioning.userId", merchantId);
            currentSpan.setAttribute("provisioning.role",   "MERCHANT");
            LOG.infof("Auth user provisioned from merchant.onboarding.started event: %s", merchantId);

            return message.ack();

        } catch (Exception e) {
            currentSpan.recordException(e);
            currentSpan.setStatus(StatusCode.ERROR, e.getMessage());
            LOG.errorf("Failed to process merchant event: %s", e.getMessage());
            return message.nack(e);
        }
    }

    private String extractDomain(String email) {
        if (email == null || !email.contains("@")) return "unknown";
        return email.substring(email.indexOf('@') + 1);
    }
}