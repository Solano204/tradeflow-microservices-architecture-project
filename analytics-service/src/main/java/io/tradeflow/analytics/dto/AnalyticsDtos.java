package io.tradeflow.analytics.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * HTTP response DTOs for all Analytics Service endpoints.
 */
public class AnalyticsDtos {

    // ================================================================
    // ENDPOINT 1 — Revenue Response
    // ================================================================

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RevenueResponse {
        @JsonProperty("merchant_id")   private String merchantId;
        private String window;
        @JsonProperty("window_start")  private Instant windowStart;
        @JsonProperty("window_end")    private Instant windowEnd;
        private RevenueMetrics revenue;
        private ComparisonMetrics comparison;
        @JsonProperty("hourly_breakdown") private List<HourlyBreakdown> hourlyBreakdown;
        @JsonProperty("data_source")   private String dataSource;   // REALTIME | HISTORICAL
        @JsonProperty("served_from")   private String servedFrom;   // ROCKSDB | MONGODB
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RevenueMetrics {
        private double total;
        private String currency;
        @JsonProperty("transaction_count")  private int transactionCount;
        @JsonProperty("average_order_value") private double averageOrderValue;
        @JsonProperty("min_order_value")    private double minOrderValue;
        @JsonProperty("max_order_value")    private double maxOrderValue;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ComparisonMetrics {
        @JsonProperty("previous_period_total")  private double previousPeriodTotal;
        @JsonProperty("change_amount")          private double changeAmount;
        @JsonProperty("change_pct")             private double changePct;
        private String trend;   // UP | DOWN | FLAT
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class HourlyBreakdown {
        private Instant hour;
        private double revenue;
        private int transactions;
    }

    // ================================================================
    // ENDPOINT 2 — Order Volume Response
    // ================================================================

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OrderVolumeResponse {
        @JsonProperty("merchant_id")    private String merchantId;
        private String window;
        private OrderMetrics orders;
        private FulfillmentMetrics fulfillment;
        private OrderComparisonMetrics comparison;
        @JsonProperty("served_from")    private String servedFrom;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OrderMetrics {
        @JsonProperty("total_created")          private long totalCreated;
        @JsonProperty("total_cancelled")        private long totalCancelled;
        @JsonProperty("cancellation_rate")      private double cancellationRate;
        @JsonProperty("cancellation_rate_pct")  private double cancellationRatePct;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FulfillmentMetrics {
        @JsonProperty("avg_acknowledgment_time_minutes") private double avgAcknowledgmentTimeMinutes;
        @JsonProperty("avg_ship_time_hours")             private double avgShipTimeHours;
        @JsonProperty("avg_delivery_days")               private double avgDeliveryDays;
        @JsonProperty("on_time_delivery_rate")           private double onTimeDeliveryRate;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OrderComparisonMetrics {
        @JsonProperty("previous_cancellation_rate")  private double previousCancellationRate;
        @JsonProperty("cancellation_trend")          private String cancellationTrend; // IMPROVING | WORSENING | STABLE
        @JsonProperty("cancellation_change_pct")     private double cancellationChangePct;
    }

    // ================================================================
    // ENDPOINT 3 — Product Performance Response
    // ================================================================

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProductPerformanceResponse {
        @JsonProperty("merchant_id")            private String merchantId;
        private String window;
        private List<ProductMetrics> products;
        @JsonProperty("total_merchant_revenue") private double totalMerchantRevenue;
        @JsonProperty("served_from")            private String servedFrom;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ProductMetrics {
        @JsonProperty("product_id")         private String productId;
        private String title;
        private double revenue;
        @JsonProperty("units_sold")         private int unitsSold;
        @JsonProperty("avg_order_value")    private double avgOrderValue;
        @JsonProperty("revenue_share_pct")  private double revenueSharePct;
        private int rank;
        @JsonProperty("rank_change")        private String rankChange;
        private String category;
    }

    // ================================================================
    // ENDPOINT 4 — Trending Products Response
    // ================================================================

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TrendingProductsResponse {
        private String category;
        private String window;
        @JsonProperty("generated_at")              private Instant generatedAt;
        @JsonProperty("trending_products")         private List<TrendingProduct> trendingProducts;
        @JsonProperty("served_from")               private String servedFrom;
        @JsonProperty("data_freshness_seconds")    private long dataFreshnessSeconds;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TrendingProduct {
        private int rank;
        @JsonProperty("product_id")     private String productId;
        private String title;
        @JsonProperty("merchant_id")    private String merchantId;
        @JsonProperty("revenue_24h")    private double revenue24h;
        @JsonProperty("units_sold_24h") private int unitsSold24h;
    }

    // ================================================================
    // ENDPOINT 5 — Merchant Summary Response
    // ================================================================

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MerchantSummaryResponse {
        @JsonProperty("merchant_id")    private String merchantId;
        @JsonProperty("generated_at")   private Instant generatedAt;
        private SummaryRevenue revenue;
        private SummaryOrders orders;
        @JsonProperty("top_products_today") private List<TopProductToday> topProductsToday;
        private List<AlertItem> alerts;
        @JsonProperty("served_from")    private String servedFrom;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SummaryRevenue {
        private double today;
        private double week;
        private double month;
        @JsonProperty("vs_last_week_pct") private double vsLastWeekPct;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SummaryOrders {
        private long today;
        @JsonProperty("pending_acknowledgment") private long pendingAcknowledgment;
        @JsonProperty("in_fulfillment")         private long inFulfillment;
        @JsonProperty("cancellation_rate_7d")   private double cancellationRate7d;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TopProductToday {
        @JsonProperty("product_id")  private String productId;
        private String title;
        private int units;
        private double revenue;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AlertItem {
        private String type;         // LOW_STOCK | CANCELLATION_RATE_HIGH | REVENUE_DROP
        @JsonProperty("product_id")  private String productId;
        @JsonProperty("available_qty") private Integer availableQty;
        private Double rate;
        private Double threshold;
        private Double changePct;
    }

    // ================================================================
    // ENDPOINT 6 — Platform Overview Response
    // ================================================================

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PlatformOverviewResponse {
        private String window;
        private PlatformMetrics platform;
        @JsonProperty("top_merchants")  private List<MerchantRankItem> topMerchants;
        @JsonProperty("top_categories") private List<CategoryRankItem> topCategories;
        @JsonProperty("served_from")    private String servedFrom;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PlatformMetrics {
        @JsonProperty("gmv_24h")           private double gmv24h;
        @JsonProperty("gmv_7d")            private double gmv7d;
        @JsonProperty("orders_24h")        private long orders24h;
        @JsonProperty("active_merchants")  private int activeMerchants;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MerchantRankItem {
        @JsonProperty("merchant_id")    private String merchantId;
        @JsonProperty("revenue_24h")    private double revenue24h;
        private int rank;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CategoryRankItem {
        private String category;
        @JsonProperty("revenue_24h")  private double revenue24h;
        @JsonProperty("share_pct")    private double sharePct;
    }

    // ================================================================
    // ENDPOINT 8 — Snapshot Status Response
    // ================================================================

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SnapshotStatusResponse {
        @JsonProperty("last_snapshot")              private SnapshotInfo lastSnapshot;
        @JsonProperty("next_scheduled_snapshot")    private Instant nextScheduledSnapshot;
        @JsonProperty("mongodb_coverage")           private CoverageInfo mongodbCoverage;
        @JsonProperty("rocksdb_coverage")           private RocksDbInfo rocksdbCoverage;
        @JsonProperty("streams_state")              private String streamsState;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SnapshotInfo {
        @JsonProperty("completed_at")           private Instant completedAt;
        @JsonProperty("duration_seconds")       private long durationSeconds;
        @JsonProperty("records_snapshotted")    private int recordsSnapshotted;
        private String status;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CoverageInfo {
        @JsonProperty("oldest_record")          private Instant oldestRecord;
        @JsonProperty("total_merchant_days")    private long totalMerchantDays;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RocksDbInfo {
        @JsonProperty("state")          private String state;
        @JsonProperty("is_queryable")   private boolean isQueryable;
    }

    // ================================================================
    // ENDPOINT 9 — Flush Response
    // ================================================================

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SnapshotTriggerResponse {
        @JsonProperty("triggered_at")       private Instant triggeredAt;
        @JsonProperty("records_snapshotted") private int recordsSnapshotted;
        @JsonProperty("duration_seconds")   private long durationSeconds;
        private String status;
        private String message;
    }

    // ================================================================
    // WebSocket live event
    // ================================================================

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LiveSalesEvent {
        private String type;            // SALE | INITIAL_STATE | ERROR
        @JsonProperty("order_id")       private String orderId;
        private Double amount;
        private String currency;
        @JsonProperty("product_title")  private String productTitle;
        @JsonProperty("buyer_city")     private String buyerCity;   // anonymized — no PII
        private Instant timestamp;
        // For INITIAL_STATE type
        @JsonProperty("current_summary") private MerchantSummaryResponse currentSummary;
    }
}
