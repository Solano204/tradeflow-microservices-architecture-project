package io.tradeflow.payment.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Payment entity.
 *
 * CRITICAL FIX — paymentMethodDetails field type:
 *
 *   The Vert.x reactive PostgreSQL client reads JSONB columns back as
 *   PGobject instances. When Hibernate Reactive tries to bind that
 *   PGobject into a Map<String,Object> parameter it fails with:
 *
 *     "Parameter at position[6] with class=[PGobject] cannot be coerced
 *      to the expected class=[java.lang.Object] for encoding"
 *
 *   The fix is to type paymentMethodDetails as String. Hibernate Reactive
 *   binds String → jsonb cleanly via the reactive PG client's text binding.
 *   The @Column columnDefinition="jsonb" ensures PostgreSQL stores it as JSONB.
 *
 *   If you need to read it back as a Map, deserialize it with Jackson in
 *   the service layer: objectMapper.readValue(payment.paymentMethodDetails, Map.class)
 */
@Entity
@Table(name = "payments",
        indexes = {
                @Index(name = "idx_payments_order_id",      columnList = "order_id", unique = true),
                @Index(name = "idx_payments_buyer_id",      columnList = "buyer_id"),
                @Index(name = "idx_payments_stripe_charge", columnList = "stripe_charge_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment extends PanacheEntityBase {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    public String id;

    @Column(name = "order_id", nullable = false, unique = true, length = 36)
    public String orderId;

    @Column(name = "buyer_id", nullable = false, length = 36)
    public String buyerId;

    @Column(name = "merchant_id", nullable = false, length = 36)
    public String merchantId;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    public BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    public String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    public PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "stripe_charge_id", length = 100)
    public String stripeChargeId;

    @Column(name = "payment_method_token", length = 200)
    public String paymentMethodToken;

    /**
     * CRITICAL: String type, not Map<String,Object>.
     *
     * The Vert.x reactive PG client binds String → jsonb cleanly.
     * Map<String,Object> causes PGobject coercion failure at position[6].
     *
     * Store as JSON string: "{\"type\":\"card\",\"last4\":\"4242\"}"
     * Read back and parse with Jackson in the service layer if needed.
     */
    @Column(name = "payment_method_details", columnDefinition = "jsonb")
    public String paymentMethodDetails;

    @Column(name = "processed_at")
    public Instant processedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Version
    @Column(name = "version")
    @Builder.Default
    public long version = 0L;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public enum PaymentStatus {
        PENDING, SUCCEEDED, FAILED, REFUNDED, PARTIAL_REFUND, DISPUTED
    }
}