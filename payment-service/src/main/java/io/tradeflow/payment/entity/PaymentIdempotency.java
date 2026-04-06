package io.tradeflow.payment.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

// ─────────────────────────────────────────────────────────────────────────────
// PAYMENTS IDEMPOTENCY — The safety net preventing double charges
//
// SELECT before calling Stripe — if key exists, return cached response.
// INSERT after Stripe responds (before publishing Kafka event).
// Unique constraint on idempotency_key: concurrent identical requests
// result in one INSERT succeeding; the other gets unique violation and
// falls back to the SELECT path.
// TTL: 7 days (Stripe's own idempotency window).
//
// CRITICAL FIX — storedResponse field type:
//
//   The Vert.x reactive PostgreSQL client reads JSONB columns back as
//   PGobject instances. When Hibernate Reactive tries to bind that
//   PGobject into a Map<String,Object> parameter it fails with:
//
//     "Parameter at position[3] with class=[PGobject] cannot be coerced
//      to the expected class=[java.lang.Object] for encoding"
//
//   Fix: type storedResponse as String. Callers must serialize Map to
//   JSON String via ObjectMapper.writeValueAsString() before storing,
//   and deserialize back with ObjectMapper.readValue() when reading.
// ─────────────────────────────────────────────────────────────────────────────
@Entity
@Table(name = "payments_idempotency")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentIdempotency extends PanacheEntityBase {

    /** The idempotency key IS the primary key */
    @Id
    @Column(name = "idempotency_key", length = 200, nullable = false)
    public String idempotencyKey;

    @Column(name = "order_id", nullable = false, length = 36)
    public String orderId;

    @Column(name = "type", length = 20)
    @Builder.Default
    public String type = "CHARGE"; // CHARGE | REFUND

    /**
     * CRITICAL: String type, not Map<String,Object>.
     *
     * The Vert.x reactive PG client binds String → jsonb cleanly.
     * Map<String,Object> causes PGobject coercion failure at position[3].
     *
     * Store: MAPPER.writeValueAsString(map)
     * Read:  MAPPER.readValue(storedResponse, new TypeReference<Map<String,Object>>(){})
     */
    @Column(name = "stored_response", nullable = false, columnDefinition = "jsonb")
    public String storedResponse;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }
}