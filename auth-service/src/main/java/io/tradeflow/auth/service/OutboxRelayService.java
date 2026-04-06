package io.tradeflow.auth.service;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import io.tradeflow.auth.config.TracingHelper;
import io.tradeflow.auth.entity.AuthOutbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.OnOverflow;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OutboxRelayService — reads unpublished rows from auth_outbox and produces to Kafka.
 *
 * TRACING — how the same traceId crosses the Kafka boundary:
 *
 * The outbox pattern means the DB write and Kafka produce happen in SEPARATE transactions.
 * The original HTTP request's traceId is stored in the outbox row via the
 * `trace_context` column (populated in AuthService.writeOutboxEvent).
 *
 * When this relay picks up the row, it:
 *   1. Restores the original OTel context from the stored trace_context
 *   2. Creates a child span under that context
 *   3. Injects W3C traceparent into the Kafka message headers
 *
 * Result: the consuming service (e.g. Identity Service) receives the Kafka message
 * with the same traceId as the original HTTP request, even though minutes may have
 * passed between the DB write and the Kafka produce.
 *
 * NOTE: If you do NOT need to link the relay span back to the original HTTP trace
 * (i.e. it's OK for Kafka produce to start a new trace), you can remove steps 1-2
 * and rely solely on the TracingProducerInterceptor configured in application.properties.
 * That interceptor automatically injects the CURRENT active span's context — which
 * in the relay's scheduled thread will be a fresh root span.
 */
@Startup
@ApplicationScoped
public class OutboxRelayService {

    private static final Logger LOG = Logger.getLogger(OutboxRelayService.class);

    private final AtomicBoolean ready = new AtomicBoolean(false);

    // TextMapSetter tells OTel how to inject context into Kafka Headers
    private static final TextMapSetter<Headers> KAFKA_HEADER_SETTER =
            (headers, key, value) -> {
                if (headers != null && key != null && value != null) {
                    // Remove existing header with same key before adding (avoid duplicates on retry)
                    headers.remove(key);
                    headers.add(key, value.getBytes());
                }
            };

    @Inject
    @Channel("auth-outbox")
    @OnOverflow(value = OnOverflow.Strategy.BUFFER, bufferSize = 256)
    Emitter<String> kafkaEmitter;

    @Inject
    TracingHelper tracingHelper;

    @ConfigProperty(name = "tradeflow.outbox.relay.batch-size", defaultValue = "50")
    int batchSize;

    void onStart(@Observes StartupEvent ev) {
        ready.set(true);
        LOG.info("OutboxRelayService: Kafka emitter ready — relay enabled");
    }

    // ----------------------------------------------------------------
    // Main relay loop — runs every 500ms
    // ----------------------------------------------------------------

    @Scheduled(every = "PT0.5S", identity = "auth-outbox-relay")
    public void relay() {
        if (!ready.get()) return;

        List<AuthOutbox> pending = fetchPendingEvents();
        if (pending.isEmpty()) return;

        LOG.debugf("Outbox relay: publishing %d events", pending.size());

        for (AuthOutbox event : pending) {
            // Each event gets its own tracing span so failures are individually visible in Zipkin
            try (TracingHelper.OtelSpan relaySpan = tracingHelper
                    .startSpan("outbox.relay")
                    .tag("event.type",     event.eventType)
                    .tag("event.id",       String.valueOf(event.id))
                    .tag("aggregate.id",   event.aggregateId)
                    .tag("retry.count",    event.retryCount)) {

                try {
                    publishToKafka(event);
                    markEventAsPublished(event);
                    relaySpan.tag("publish.status", "success");

                } catch (Exception e) {
                    relaySpan.setError(e);
                    LOG.errorf("Failed to publish outbox event %d (%s): %s",
                            event.id, event.eventType, e.getMessage());
                    recordFailure(event, e.getMessage());
                }
            }
        }
    }

    // ----------------------------------------------------------------
    // Transactional helpers (each in its own transaction)
    // ----------------------------------------------------------------

    @Transactional
    List<AuthOutbox> fetchPendingEvents() {
        return AuthOutbox.findUnpublished(batchSize);
    }

    @Transactional
    void markEventAsPublished(AuthOutbox event) {
        AuthOutbox freshEvent = AuthOutbox.findById(event.id);
        if (freshEvent != null && !freshEvent.published) {
            freshEvent.published    = true;
            freshEvent.publishedAt  = Instant.now();
            freshEvent.persist();
        }
    }

    @Transactional
    void recordFailure(AuthOutbox event, String errorMessage) {
        AuthOutbox freshEvent = AuthOutbox.findById(event.id);
        if (freshEvent != null) {
            freshEvent.retryCount++;
            freshEvent.lastError = errorMessage;
            freshEvent.persist();
        }
    }

    // ----------------------------------------------------------------
    // Cleanup jobs
    // ----------------------------------------------------------------

    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        long deleted = AuthOutbox.deleteOldPublished(cutoff);
        if (deleted > 0) {
            LOG.infof("Outbox cleanup: deleted %d old published events", deleted);
        }
    }

    @Scheduled(every = "PT15M", identity = "pkce-state-cleanup")
    @Transactional
    public void cleanupPkceState() {
        if (!ready.get()) return;
        long deleted = io.tradeflow.auth.entity.PkceState.deleteExpired();
        if (deleted > 0) {
            LOG.debugf("PKCE state cleanup: deleted %d expired states", deleted);
        }
    }

    // ----------------------------------------------------------------
    // Kafka publish — injects W3C traceparent into message headers
    // ----------------------------------------------------------------

    /**
     * Publish an outbox event to Kafka.
     *
     * OTel context injection happens here:
     * - We build a RecordHeaders object
     * - We call OTel's TextMapPropagator.inject() with our KAFKA_HEADER_SETTER
     *   which writes the "traceparent" (and optionally "tracestate") headers
     *   into the Kafka message
     * - The consuming service's TracingConsumerInterceptor reads these headers
     *   and resumes the same traceId
     *
     * This works in addition to the TracingProducerInterceptor configured in
     * application.properties — both mechanisms inject context, ensuring compatibility
     * with any OTel-instrumented consumer regardless of how it reads trace headers.
     */
    private void publishToKafka(AuthOutbox event) {
        RecordHeaders headers = new RecordHeaders();

        // Add business headers
        headers.add("event_type", event.eventType.getBytes());
        headers.add("event_id",   String.valueOf(event.id).getBytes());

        // ✅ Inject current OTel context (W3C traceparent + tracestate) into Kafka headers
        // This is what carries the traceId to the consuming service
        GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .inject(Context.current(), headers, KAFKA_HEADER_SETTER);

        Message<String> message = Message.of(event.payload)
                .addMetadata(OutgoingKafkaRecordMetadata.<String>builder()
                        .withKey(event.aggregateId)
                        .withHeaders(headers)
                        .build());

        kafkaEmitter.send(message);
        LOG.debugf("Published event: %s (aggregate: %s, traceId: %s)",
                event.eventType,
                event.aggregateId,
                Span.current().getSpanContext().getTraceId());
    }
}