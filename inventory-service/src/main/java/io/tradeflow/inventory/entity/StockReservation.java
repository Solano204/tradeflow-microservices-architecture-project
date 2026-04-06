package io.tradeflow.inventory.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "stock_reservations",
        indexes = {
                @Index(name = "idx_reservations_inventory_id",   columnList = "inventory_id"),
                @Index(name = "idx_reservations_order_id",       columnList = "order_id"),
                @Index(name = "idx_reservations_status_expiry",  columnList = "status, expires_at"),
                @Index(name = "idx_reservations_idempotency",    columnList = "idempotency_key", unique = true)
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockReservation {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "inventory_id", nullable = false, length = 36)
    private String inventoryId;

    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    @Column(name = "buyer_id", nullable = false, length = 36)
    private String buyerId;

    @Column(name = "qty", nullable = false)
    private int qty;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ReservationStatus status = ReservationStatus.PENDING;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 200)
    private String idempotencyKey;

    @Column(name = "release_reason", length = 100)
    private String releaseReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public boolean isPending() { return status == ReservationStatus.PENDING; }
    public boolean isExpired(Instant now) {
        return status == ReservationStatus.PENDING && expiresAt.isBefore(now);
    }

    public enum ReservationStatus { PENDING, CONFIRMED, RELEASED, EXPIRED }
}
