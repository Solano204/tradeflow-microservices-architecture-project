package io.tradeflow.inventory.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

// ─────────────────────────────────────────────────────────────────────────────
// STOCK RESERVATION — The two-phase locking mechanism
//
// Phase 1 (reserve):  INSERT with status=PENDING, expires_at = NOW() + TTL
// Phase 2a (confirm): UPDATE status=CONFIRMED + decrement inventory.total_qty
// Phase 2b (release): UPDATE status=RELEASED   + total_qty unchanged
// TTL cleanup:        UPDATE status=EXPIRED     + total_qty unchanged
//
// available_qty = total_qty - SUM(PENDING reservations WHERE expires_at > NOW())
// CONFIRMED/RELEASED/EXPIRED rows are excluded from the formula → availability
// restores automatically on release/expiry without any UPDATE to inventory.total_qty.
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// INVENTORY OUTBOX — Transactional outbox pattern
// Every state change writes an outbox row in the SAME @Transactional block.
// Outbox relay (500ms @Scheduled, SKIP LOCKED) forwards to Kafka.
// Zero lost events under Kafka downtime — events queue in PostgreSQL.
// ─────────────────────────────────────────────────────────────────────────────

@Entity
@Table(name = "inventory_outbox",
        indexes = {
                @Index(name = "idx_inventory_outbox_unpublished", columnList = "published, created_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryOutbox {

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
