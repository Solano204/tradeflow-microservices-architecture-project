package io.tradeflow.catalog.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "products",
        indexes = {
                @Index(name = "idx_products_merchant", columnList = "merchant_id"),
                @Index(name = "idx_products_category_status", columnList = "category_id, status"),
                @Index(name = "idx_products_status", columnList = "status"),
                @Index(name = "idx_products_category_price", columnList = "category_id, price"),
                @Index(name = "idx_products_sales_volume", columnList = "sales_volume DESC")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "merchant_id", nullable = false, length = 36)
    private String merchantId;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "category_id", nullable = false, length = 100)
    private String categoryId;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "MXN";

    @Enumerated(EnumType.STRING)
    @Column(name = "price_mode", nullable = false, length = 20)
    @Builder.Default
    private PriceMode priceMode = PriceMode.FIXED;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ProductStatus status = ProductStatus.DRAFT;

    @Column(name = "stock_ref", length = 100)
    private String stockRef;

    // Promotional pricing fields — populated when price_mode = PROMOTIONAL
    @Column(name = "original_price", precision = 12, scale = 2)
    private BigDecimal originalPrice;

    @Column(name = "promotional_price", precision = 12, scale = 2)
    private BigDecimal promotionalPrice;

    @Column(name = "promotional_start")
    private Instant promotionalStart;

    @Column(name = "promotional_end")
    private Instant promotionalEnd;

    // Tracks sales for cache pre-warming (top 1000 hot products)
    @Column(name = "sales_volume", nullable = false)
    @Builder.Default
    private Long salesVolume = 0L;

    @Column(name = "delisted_at")
    private Instant delistedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Optimistic lock — prevents concurrent edit conflicts (409 on clash)
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // ── State machine helpers ─────────────────────────────────────────────

    public boolean canTransitionTo(ProductStatus target) {
        return switch (status) {
            case DRAFT       -> target == ProductStatus.ACTIVE;
            case ACTIVE      -> target == ProductStatus.SUSPENDED || target == ProductStatus.DELISTED;
            case SUSPENDED   -> target == ProductStatus.ACTIVE || target == ProductStatus.DELISTED;
            case DELISTED    -> false;  // terminal — no going back
        };
    }

    public boolean isPromotionReady(Instant now) {
        return priceMode == PriceMode.PROMOTIONAL
                && promotionalStart != null
                && !promotionalStart.isAfter(now)
                && status == ProductStatus.ACTIVE;
    }

    public boolean isPromotionExpired(Instant now) {
        return priceMode == PriceMode.PROMOTIONAL
                && promotionalEnd != null
                && promotionalEnd.isBefore(now);
    }

    // ── Enums ─────────────────────────────────────────────────────────────

    public enum ProductStatus { DRAFT, ACTIVE, SUSPENDED, DELISTED }
    public enum PriceMode { FIXED, TIERED, PROMOTIONAL }
}
