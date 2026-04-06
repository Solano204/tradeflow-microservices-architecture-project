package io.tradeflow.order.service;

import io.tradeflow.order.entity.*;
import io.tradeflow.order.entity.Order.OrderStatus;
import io.tradeflow.order.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

// ─────────────────────────────────────────────────────────────────────────────
// OUTBOX RELAY — forwards SAGA commands and domain events to Kafka every 500ms
// SELECT FOR UPDATE SKIP LOCKED: multi-pod safe, no duplicate publishes
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// SAGA RECOVERY JOB — The Self-Healing Engine
//
// Every 60 seconds, scans for orders stuck in intermediate SAGA states.
// These represent: crashed Pods, lost Kafka messages, network partitions.
//
// Strategy per state:
//   CREATED > 10min          → re-emit cmd.reserve-inventory
//   INVENTORY_RESERVED > 10min → re-emit cmd.score-transaction
//   PAYMENT_PENDING > 15min   → compensation or manual advance
//
// ShedLock ensures only ONE Pod runs this per 60-second window.
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// EXCEPTIONS
// ─────────────────────────────────────────────────────────────────────────────

@ResponseStatus(HttpStatus.NOT_FOUND)
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) { super(message); }
}

