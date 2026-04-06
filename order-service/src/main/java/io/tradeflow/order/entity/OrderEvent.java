package io.tradeflow.order.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

// ─────────────────────────────────────────────────────────────────────────────
// ORDER EVENT — The append-only event store (source of truth)
//
// Every state transition appends a row here. Never updated. Never deleted.
// State is the result of replaying these events in version order.
//
// Enables:
//   - Perfect audit trail (compliance, disputes)
//   - Time travel: reconstruct state at any historical point
//   - SAGA debugging: see exactly which step failed and when
//   - Recovery: restart SAGA from any known-good event
// ─────────────────────────────────────────────────────────────────────────────
@Entity
@Table(name = "order_events",
        indexes = {
                @Index(name = "idx_order_events_order_id", columnList = "order_id, version"),
                @Index(name = "idx_order_events_type",     columnList = "event_type")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    /** Monotonic per-order version counter (1, 2, 3...) */
    @Column(name = "version", nullable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> eventPayload;

    /** Which HTTP request or Kafka message triggered this event */
    @Column(name = "causation_id", length = 100)
    private String causationId;

    /** The root order_id — for distributed trace linkage */
    @Column(name = "correlation_id", length = 36)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @PrePersist
    void onCreate() { occurredAt = Instant.now(); }
}
