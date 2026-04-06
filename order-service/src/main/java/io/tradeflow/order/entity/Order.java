package io.tradeflow.order.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

// ─────────────────────────────────────────────────────────────────────────────
// ORDER — Materialized snapshot (read-performance optimization)
//
// The SOURCE OF TRUTH is order_events (append-only, event sourcing).
// This table is a PROJECTION — rebuilt by replaying all events.
// Kept in sync in the same @Transactional block as every event INSERT.
//
// If this snapshot drifts, it can be rebuilt at any time by replaying events.
// PostgreSQL is authoritative; this row is a convenience.
// ─────────────────────────────────────────────────────────────────────────────
@Entity
@Table(name = "orders",
        indexes = {
                @Index(name = "idx_orders_buyer_id",        columnList = "buyer_id, created_at"),
                @Index(name = "idx_orders_status",          columnList = "status"),
                @Index(name = "idx_orders_merchant_id",     columnList = "merchant_id"),
                @Index(name = "idx_orders_idempotency",     columnList = "idempotency_key", unique = true),
                @Index(name = "idx_orders_status_event_at", columnList = "status, last_event_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "buyer_id", nullable = false, length = 36)
    private String buyerId;

    @Column(name = "merchant_id", nullable = false, length = 36)
    private String merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    @Builder.Default
    private OrderStatus status = OrderStatus.CREATED;

    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "MXN";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shipping_address", columnDefinition = "jsonb")
    private Map<String, Object> shippingAddress;

    @Column(name = "payment_method_token", length = 200)
    private String paymentMethodToken;

    @Column(name = "reservation_id", length = 36)
    private String reservationId;

    @Column(name = "charge_id", length = 100)
    private String chargeId;

    @Column(name = "tracking_number", length = 100)
    private String trackingNumber;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 200)
    private String idempotencyKey;

    @Column(name = "last_event_version", nullable = false)
    @Builder.Default
    private int lastEventVersion = 0;

    @Column(name = "last_event_at")
    private Instant lastEventAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    @Builder.Default
    private long version = 0L;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); updatedAt = Instant.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    // ── Status helpers ─────────────────────────────────────────────────────────

    public boolean isInTerminalState() {
        return switch (status) {
            case PAID, DELIVERED, CANCELLED, REJECTED, PAYMENT_FAILED -> true;
            default -> false;
        };
    }

    public enum OrderStatus {
        CREATED, INVENTORY_RESERVED, FRAUD_SCORING, PAYMENT_PENDING,
        PAID, FULFILLING, SHIPPED, DELIVERED,
        CANCELLED, REJECTED, PAYMENT_FAILED, FRAUD_REVIEW,
        RETURN_REQUESTED, RETURN_APPROVED, REFUNDED
    }
}
