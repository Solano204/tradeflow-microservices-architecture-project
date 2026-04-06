package io.tradeflow.inventory.service;

import io.tradeflow.inventory.entity.InventoryOutbox;
import io.tradeflow.inventory.repository.InventoryOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.List;
import java.util.Map;

// ─────────────────────────────────────────────────────────────────────────────
// OUTBOX RELAY — publishes inventory events to Kafka every 500ms
// SELECT FOR UPDATE SKIP LOCKED: multi-pod safe, no duplicate publishes
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// EXCEPTIONS
// ─────────────────────────────────────────────────────────────────────────────

@ResponseStatus(HttpStatus.NOT_FOUND)
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) { super(message); }
}

