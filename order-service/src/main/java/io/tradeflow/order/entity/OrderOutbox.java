package io.tradeflow.order.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

// ─────────────────────────────────────────────────────────────────────────────
// ORDER OUTBOX — Transactional command and event bus
//
// SAGA commands (cmd.reserve-inventory, cmd.score-transaction, cmd.charge-payment)
// and domain events (event.order-paid, event.order-cancelled) are written here
// in the SAME @Transactional as the order_events INSERT.
//
// Outbox relay (500ms, SKIP LOCKED) forwards to Kafka.
// Zero lost commands/events even if Kafka is temporarily down.
// ─────────────────────────────────────────────────────────────────────────────
@Entity
@Table(name = "order_outbox",
        indexes = {
                @Index(name = "idx_order_outbox_unpublished", columnList = "published, created_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "aggregate_id", length = 36)
    private String aggregateId;

    @Column(name = "published", nullable = false)
    @Builder.Default
    private boolean published = false;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }
}
