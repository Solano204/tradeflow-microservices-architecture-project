package io.tradeflow.payment.service;

import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import io.tradeflow.payment.entity.PaymentOutbox;
import io.tradeflow.payment.repository.PaymentOutboxRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * OutboxRelayService — forwards payment events to Kafka every 1 second.
 *
 * TRACING — how traceparent gets injected automatically:
 * ─────────────────────────────────────────────────────────────────────────────
 * No manual tracing code is needed here. Here is what happens:
 *
 * When MockPaymentService (or PaymentService) writes to payment_outbox, it runs
 * inside a @WithTransaction that is itself a child span of the Kafka consumer
 * span (which has the traceId from Order Service).
 *
 * This @Scheduled relay runs on a separate timer thread with NO active span.
 * However: quarkus-opentelemetry with quarkus.otel.instrument.messaging=true
 * automatically wraps every emitter.send() call and:
 *   1. Creates a new "messaging.publish" span
 *   2. Injects that span's traceparent into the outgoing Kafka message header
 *
 * IMPORTANT LIMITATION:
 * Because the outbox relay runs on its own @Scheduled thread (not inside the
 * original Kafka consumer span), the traceId in the outgoing payment.events
 * message will be a NEW traceId — not the original Order Service traceId.
 *
 * This is the known trade-off of the outbox pattern with async relay:
 *   - The INBOUND trace (cmd.charge-payment → DB writes) preserves the traceId ✅
 *   - The OUTBOUND trace (relay → payment.events) starts a new trace ⚠️
 *
 * TO PRESERVE THE TRACEId ACROSS THE OUTBOX:
 * Store the traceparent string in the PaymentOutbox.traceContext column, then
 * restore it here before calling emitter.send(). This is an optional enhancement
 * that requires a schema migration. For now, Order Service can correlate by orderId.
 *
 * ORIGINAL FIX (unchanged):
 * ─────────────────────────────────────────────────────────────────────────────
 * event.payload is now String (not Map<String,Object>).
 * OutboxRelayService sends event.payload directly — it is already a JSON string.
 *
 * Old code: emitter.send(JsonObject.mapFrom(event.payload).encode())
 * New code: emitter.send(event.payload)
 */
@ApplicationScoped
@Slf4j
public class OutboxRelayService {

    @Inject
    PaymentOutboxRepository outboxRepo;

    @Inject @Channel("payment-processed") Emitter<String> processedEmitter;
    @Inject @Channel("payment-failed")    Emitter<String> failedEmitter;
    @Inject @Channel("refund-completed")  Emitter<String> refundEmitter;
    @Inject @Channel("dispute-created")   Emitter<String> disputeEmitter;
    @Inject @Channel("order-events")      Emitter<String> orderEventsEmitter;

    @Scheduled(every = "PT1S")
    @WithSession
    Uni<Void> relay() {
        return outboxRepo.findUnpublished(100)
                .flatMap(batch -> {
                    if (batch.isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }

                    List<String> published = batch.stream()
                            .filter(this::tryPublish)
                            .map(e -> e.id)
                            .collect(Collectors.toList());

                    if (published.isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }

                    return outboxRepo.markPublished(published)
                            .invoke(count -> log.debug(
                                    "Outbox relay: published {} payment events", count))
                            .replaceWithVoid();
                })
                .onFailure().invoke(err ->
                        log.error("Outbox relay failed: {}", err.getMessage()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    private boolean tryPublish(PaymentOutbox event) {
        try {
            Emitter<String> emitter = switch (event.eventType) {
                case "payment.processed" -> processedEmitter;
                case "payment.failed"    -> failedEmitter;
                case "refund.completed"  -> refundEmitter;
                case "dispute.created"   -> disputeEmitter;
                default                  -> orderEventsEmitter;
            };

            // CRITICAL: event.payload is already a JSON String — send directly.
            // quarkus-opentelemetry with quarkus.otel.instrument.messaging=true
            // automatically adds traceparent header to the outgoing Kafka message.
            emitter.send(event.payload);
            return true;

        } catch (Exception e) {
            log.error("Failed to publish outbox event id={} type={}: {}",
                    event.id, event.eventType, e.getMessage());
            return false;
        }
    }
}