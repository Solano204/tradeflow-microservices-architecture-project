package io.tradeflow.analytics.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Kafka event payload DTOs consumed by Kafka Streams topologies
 * and by the live WebSocket KafkaListener.
 */
public class KafkaEventDtos {

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PaymentProcessedEvent {
        @JsonProperty("order_id")       private String orderId;
        @JsonProperty("merchant_id")    private String merchantId;
        @JsonProperty("buyer_id")       private String buyerId;
        @JsonProperty("buyer_city")     private String buyerCity;   // anonymized — no full address
        private BigDecimal amount;
        private String currency;
        private String status;          // SUCCESS | FAILED | REFUNDED
        @JsonProperty("stripe_charge_id") private String stripeChargeId;
        private List<OrderItem> items;
        @JsonProperty("occurred_at")    private Instant occurredAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderCreatedEvent {
        @JsonProperty("order_id")   private String orderId;
        @JsonProperty("merchant_id") private String merchantId;
        @JsonProperty("buyer_id")   private String buyerId;
        @JsonProperty("created_at") private Instant createdAt;
        @JsonProperty("total_amount") private BigDecimal totalAmount;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderCancelledEvent {
        @JsonProperty("order_id")    private String orderId;
        @JsonProperty("merchant_id") private String merchantId;
        @JsonProperty("cancelled_at") private Instant cancelledAt;
        private String reason;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProductCatalogUpdatedEvent {
        @JsonProperty("product_id")   private String productId;
        @JsonProperty("merchant_id")  private String merchantId;
        private String title;
        @JsonProperty("category_id")  private String categoryId;
        @JsonProperty("category_path") private String categoryPath;
        private BigDecimal price;
        private String status;        // ACTIVE | INACTIVE | DELETED
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderItem {
        @JsonProperty("product_id")  private String productId;
        @JsonProperty("merchant_id") private String merchantId;
        @JsonProperty("category_id") private String categoryId;
        private String title;
        private int qty;
        private BigDecimal subtotal;
    }
}
