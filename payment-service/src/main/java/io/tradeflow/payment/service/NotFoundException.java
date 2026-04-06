package io.tradeflow.payment.service;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import io.tradeflow.payment.entity.PaymentOutbox;
import io.tradeflow.payment.repository.PaymentOutboxRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import net.javacrumbs.shedlock.core.LockingTaskExecutor.Task;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor.Task;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// ─────────────────────────────────────────────────────────────────────────────
// OUTBOX RELAY — forwards payment events to Kafka every 500ms
//
// SELECT FOR UPDATE SKIP LOCKED: multi-pod safe, no duplicate publishes.
// If Kafka is down, events accumulate here and publish when Kafka recovers.
// The outbox is WHY payment events are never lost between Stripe and Kafka.
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// EXCEPTIONS
// ─────────────────────────────────────────────────────────────────────────────

public class NotFoundException extends WebApplicationException {
    public NotFoundException(String message) {
        super(message, Response.Status.NOT_FOUND);
    }
}

