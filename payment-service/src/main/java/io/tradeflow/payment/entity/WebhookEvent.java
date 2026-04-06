package io.tradeflow.payment.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

// ─────────────────────────────────────────────────────────────────────────────
// WEBHOOK EVENTS — PostgreSQL registry
//
// stripe_event_id has UNIQUE constraint — deduplication.
// Full Stripe payload stored in MongoDB (payment_audit) — schemaless blob.
// ─────────────────────────────────────────────────────────────────────────────
@Entity
@Table(name = "webhook_events",
        indexes = {
                @Index(name = "idx_webhook_stripe_event_id", columnList = "stripe_event_id", unique = true),
                @Index(name = "idx_webhook_type",            columnList = "event_type, received_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class  WebhookEvent extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    public String id;

    @Column(name = "stripe_event_id", nullable = false, unique = true, length = 100)
    public String stripeEventId;

    @Column(name = "event_type", nullable = false, length = 100)
    public String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    public Map<String, Object> payload;

    @Column(name = "processing_status", length = 20)
    @Builder.Default
    public String processingStatus = "PROCESSED";

    @Column(name = "received_at", nullable = false, updatable = false)
    public Instant receivedAt;

    @Column(name = "processed_at")
    public Instant processedAt;

    @PrePersist
    void onCreate() { receivedAt = Instant.now(); }
}
