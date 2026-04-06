package io.tradeflow.inventory.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Inventory row — PostgreSQL sole source of truth.
 *
 * CRITICAL DESIGN DECISION: available_qty is NOT a stored column.
 * It is always computed:
 *   available_qty = total_qty - SUM(PENDING reservations WHERE expires_at > NOW())
 *
 * Why: Storing it creates a race condition. Two concurrent reservations could
 * both read available_qty=1, both decrement to 0, both succeed — overselling.
 *
 * The @Version column is the optimistic lock. Only one concurrent writer can
 * win the "UPDATE ... WHERE version=?" compare-and-swap. The loser retries
 * (Resilience4j @Retry, 3 attempts with exponential backoff).
 */
@Entity
@Table(name = "inventory",
        indexes = {
                @Index(name = "idx_inventory_product_id", columnList = "product_id", unique = true),
                @Index(name = "idx_inventory_merchant_id", columnList = "merchant_id"),
                @Index(name = "idx_inventory_status", columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Inventory {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "product_id", nullable = false, unique = true, length = 36)
    private String productId;

    @Column(name = "merchant_id", nullable = false, length = 36)
    private String merchantId;

    @Column(name = "sku", length = 100)
    private String sku;

    @Column(name = "total_qty", nullable = false)
    private int totalQty;

    @Column(name = "low_stock_threshold", nullable = false)
    @Builder.Default
    private int lowStockThreshold = 10;

    @Column(name = "reservation_ttl_minutes", nullable = false)
    @Builder.Default
    private int reservationTtlMinutes = 10;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private InventoryStatus status = InventoryStatus.IN_STOCK;

    /**
     * JPA @Version — the core of the optimistic locking strategy.
     *
     * Every write (reserve, confirm, restock) fires:
     *   UPDATE inventory SET ..., version = version + 1 WHERE id = ? AND version = ?
     *
     * If two concurrent writes race, only one version=N matches. The other
     * gets 0 rows updated → JPA throws OptimisticLockException → Resilience4j retries.
     *
     * This is a compare-and-swap at the PostgreSQL level. No pessimistic row locks needed.
     */
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // ── Status helpers ────────────────────────────────────────────────────────

    public void updateStatusFromAvailable(int availableQty) {
        if (availableQty <= 0) {
            status = InventoryStatus.OUT_OF_STOCK;
        } else if (availableQty <= lowStockThreshold) {
            status = InventoryStatus.LOW_STOCK;
        } else {
            status = InventoryStatus.IN_STOCK;
        }
    }

    public boolean wasOutOfStock() {
        return status == InventoryStatus.OUT_OF_STOCK;
    }

    public boolean wasLowStock() {
        return status == InventoryStatus.LOW_STOCK;
    }

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum InventoryStatus { IN_STOCK, LOW_STOCK, OUT_OF_STOCK }
}
