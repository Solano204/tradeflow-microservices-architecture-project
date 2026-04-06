package io.tradeflow.order.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

// ─────────────────────────────────────────────────────────────────────────────
// ORDER ITEM — Price and title snapshotted at order creation
//
// unit_price: captured at the millisecond POST /orders fires — locked in.
// product_title: captured so order history shows what was purchased
//   even if the product is delisted or renamed later.
// ─────────────────────────────────────────────────────────────────────────────
@Entity
@Table(name = "order_items",
        indexes = {
                @Index(name = "idx_order_items_order_id",   columnList = "order_id"),
                @Index(name = "idx_order_items_product_id", columnList = "product_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    @Column(name = "product_id", nullable = false, length = 36)
    private String productId;

    @Column(name = "merchant_id", nullable = false, length = 36)
    private String merchantId;

    @Column(name = "qty", nullable = false)
    private int qty;

    /** Snapshotted at order creation — immutable. Merchant price changes don't affect this. */
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    /** Snapshotted at order creation — survives product delist/rename. */
    @Column(name = "product_title", nullable = false, length = 300)
    private String productTitle;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public BigDecimal subtotal() {
        return unitPrice.multiply(BigDecimal.valueOf(qty));
    }
}
