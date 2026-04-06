package io.tradeflow.catalog.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "pricing_rules",
        indexes = {
                @Index(name = "idx_pricing_product", columnList = "product_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PricingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "product_id", nullable = false, length = 36)
    private String productId;

    @Column(name = "rule_type", nullable = false, length = 20)
    private String ruleType;    // TIER | PROMOTIONAL

    // Tiered pricing fields
    @Column(name = "tier_min_qty")
    private Integer tierMinQty;

    @Column(name = "tier_discount_pct", precision = 5, scale = 2)
    private BigDecimal tierDiscountPct;

    // Promotional pricing fields
    @Column(name = "promotional_start")
    private Instant promotionalStart;

    @Column(name = "promotional_end")
    private Instant promotionalEnd;

    @Column(name = "promotional_price", precision = 12, scale = 2)
    private BigDecimal promotionalPrice;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() { createdAt = Instant.now(); }
}
