package io.tradeflow.payment.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * PaymentOutbox — Transactional event bus.
 *
 * CRITICAL FIX — payload field type:
 *
 *   The Vert.x reactive PostgreSQL client reads JSONB columns back as
 *   PGobject instances. When Hibernate Reactive tries to bind that
 *   PGobject into a Map<String,Object> parameter it fails with:
 *
 *     "Parameter at position[3] with class=[PGobject] cannot be coerced
 *      to the expected class=[java.lang.Object] for encoding"
 *
 *   The fix is to type payload as String. Hibernate Reactive binds
 *   String → jsonb cleanly via the reactive PG client's text binding.
 *
 *   All callers (MockPaymentService, PaymentService, OutboxRelayService)
 *   must serialize Map → JSON String before setting payload, and
 *   OutboxRelayService can send event.payload directly (already a String).
 */
@Entity
@Table(name = "payment_outbox",
        indexes = {
                @Index(name = "idx_payment_outbox_unpublished", columnList = "published, created_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentOutbox extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    public String id;

    @Column(name = "event_type", nullable = false, length = 100)
    public String eventType;

    /**
     * CRITICAL: String type, not Map<String,Object>.
     *
     * Store as pre-serialized JSON string.
     * Use Jackson ObjectMapper.writeValueAsString(map) before setting this field.
     * OutboxRelayService can send this directly via emitter.send(event.payload).
     */
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    public String payload;

    @Column(name = "aggregate_id", length = 36)
    public String aggregateId;

    @Column(name = "published", nullable = false)
    @Builder.Default
    public boolean published = false;

    @Column(name = "published_at")
    public Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}