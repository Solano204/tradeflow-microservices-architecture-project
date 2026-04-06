package io.tradeflow.analytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tradeflow.analytics.dto.AnalyticsDtos.*;
import io.tradeflow.analytics.entity.MerchantRevenueSnapshot;
import io.tradeflow.analytics.repository.MerchantRevenueSnapshotRepository;
import io.tradeflow.analytics.streams.CategoryTopProducts;
import io.tradeflow.analytics.streams.RevenueAggregate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Orchestrates analytics queries:
 * - Short windows (≤7d): served from RocksDB via InteractiveQueriesService
 * - Long windows (>7d): served from MongoDB historical snapshots
 * - Trending products: RocksDB + Redis cache layer
 * - Platform overview: full RocksDB scan + Redis cache
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsQueryService {

    private static final int ROCKSDB_MAX_DAYS = 7;
    private static final double CANCELLATION_ALERT_THRESHOLD = 0.05;
    private static final double REVENUE_DROP_THRESHOLD = -20.0;

    private final InteractiveQueriesService interactiveQueries;
    private final MerchantRevenueSnapshotRepository revenueSnapshotRepo;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${analytics.cache.trending-ttl-seconds:60}")
    private long trendingTtl;

    @Value("${analytics.cache.platform-overview-ttl-seconds:300}")
    private long platformOverviewTtl;

    // ================================================================
    // ENDPOINT 1 — Revenue
    // ================================================================

    public RevenueResponse getMerchantRevenue(String merchantId, String window,
                                               boolean comparePrevious) {
        DataSource source = resolveDataSource(window);
        Instant now = Instant.now();
        Instant windowStart = now.minus(parseDurationDays(window), ChronoUnit.DAYS)
            .minus(parseDurationHours(window), ChronoUnit.HOURS);

        if (source == DataSource.ROCKSDB) {
            RevenueAggregate agg = interactiveQueries.getMerchantRevenue(
                merchantId, window, windowStart, now);

            List<HourlyBreakdown> breakdown = interactiveQueries
                .getMerchantRevenueHourlyBreakdown(merchantId, windowStart, now)
                .stream()
                .map(e -> HourlyBreakdown.builder()
                    .hour(Instant.ofEpochMilli(e.getKey()))
                    .revenue(e.getValue().getTotalRevenue())
                    .transactions(e.getValue().getTransactionCount())
                    .build())
                .collect(Collectors.toList());

            ComparisonMetrics comparison = null;
            if (comparePrevious) {
                Duration windowDuration = Duration.between(windowStart, now);
                Instant prevEnd = windowStart;
                Instant prevStart = prevEnd.minus(windowDuration);
                RevenueAggregate prevAgg = interactiveQueries.getMerchantRevenue(
                    merchantId, window, prevStart, prevEnd);
                comparison = buildComparison(agg.getTotalRevenue(), prevAgg.getTotalRevenue());
            }

            return RevenueResponse.builder()
                .merchantId(merchantId)
                .window(window)
                .windowStart(windowStart)
                .windowEnd(now)
                .revenue(toRevenueMetrics(agg))
                .comparison(comparison)
                .hourlyBreakdown(breakdown)
                .dataSource("REALTIME")
                .servedFrom("ROCKSDB")
                .build();

        } else {
            // MongoDB historical path for 30d, 90d
            return getHistoricalRevenue(merchantId, window, now, comparePrevious);
        }
    }

    // ================================================================
    // ENDPOINT 2 — Order Volume
    // ================================================================

    public OrderVolumeResponse getMerchantOrders(String merchantId, String window,
                                                  boolean comparePrevious) {
        Instant now = Instant.now();
        Instant windowStart = windowStart(window, now);

        long created = interactiveQueries.getMerchantOrdersCreated(merchantId, windowStart, now);
        long cancelled = interactiveQueries.getMerchantOrdersCancelled(merchantId, windowStart, now);
        double rate = created > 0 ? (double) cancelled / created : 0.0;

        OrderComparisonMetrics comparison = null;
        if (comparePrevious) {
            Duration windowDuration = Duration.between(windowStart, now);
            Instant prevStart = windowStart.minus(windowDuration);
            long prevCreated = interactiveQueries.getMerchantOrdersCreated(merchantId, prevStart, windowStart);
            long prevCancelled = interactiveQueries.getMerchantOrdersCancelled(merchantId, prevStart, windowStart);
            double prevRate = prevCreated > 0 ? (double) prevCancelled / prevCreated : 0.0;
            double changePct = prevRate > 0 ? ((rate - prevRate) / prevRate) * 100 : 0.0;
            String trend = rate > prevRate ? "WORSENING" : rate < prevRate ? "IMPROVING" : "STABLE";
            comparison = OrderComparisonMetrics.builder()
                .previousCancellationRate(prevRate)
                .cancellationTrend(trend)
                .cancellationChangePct(changePct)
                .build();
        }

        return OrderVolumeResponse.builder()
            .merchantId(merchantId)
            .window(window)
            .orders(OrderMetrics.builder()
                .totalCreated(created)
                .totalCancelled(cancelled)
                .cancellationRate(rate)
                .cancellationRatePct(rate * 100)
                .build())
            .fulfillment(FulfillmentMetrics.builder()
                .avgAcknowledgmentTimeMinutes(0)  // sourced from Order Service in future
                .onTimeDeliveryRate(0.94)
                .build())
            .comparison(comparison)
            .servedFrom("ROCKSDB")
            .build();
    }

    // ================================================================
    // ENDPOINT 3 — Product Performance
    // ================================================================

    public ProductPerformanceResponse getMerchantProducts(String merchantId, String window) {
        Instant now = Instant.now();
        Instant windowStart = windowStart(window, now);

        List<CategoryTopProducts.ProductRevenueSummary> products =
            interactiveQueries.getMerchantProducts(merchantId, windowStart, now);

        double totalRevenue = products.stream()
            .mapToDouble(CategoryTopProducts.ProductRevenueSummary::getRevenue)
            .sum();

        AtomicInteger rank = new AtomicInteger(1);
        List<ProductMetrics> metrics = products.stream()
            .sorted(Comparator.comparingDouble(CategoryTopProducts.ProductRevenueSummary::getRevenue).reversed())
            .map(p -> ProductMetrics.builder()
                .productId(p.getProductId())
                .title(p.getTitle())
                .revenue(p.getRevenue())
                .unitsSold(p.getUnitsSold())
                .avgOrderValue(p.getUnitsSold() > 0 ? p.getRevenue() / p.getUnitsSold() : 0)
                .revenueSharePct(totalRevenue > 0 ? (p.getRevenue() / totalRevenue) * 100 : 0)
                .rank(rank.getAndIncrement())
                .rankChange("0")
                .category(p.getCategoryId())
                .build())
            .collect(Collectors.toList());

        return ProductPerformanceResponse.builder()
            .merchantId(merchantId)
            .window(window)
            .products(metrics)
            .totalMerchantRevenue(totalRevenue)
            .servedFrom("ROCKSDB")
            .build();
    }

    // ================================================================
    // ENDPOINT 4 — Trending Products (with Redis cache)
    // ================================================================

    public TrendingProductsResponse getTrendingProducts(String category, int limit) {
        String cacheKey = "trending:" + category + ":24h";
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                TrendingProductsResponse response = objectMapper.readValue(
                    cached, TrendingProductsResponse.class);
                long freshness = Duration.between(
                    response.getGeneratedAt(), Instant.now()).getSeconds();
                response.setDataFreshnessSeconds(freshness);
                return response;
            }
        } catch (Exception e) {
            log.warn("Redis cache miss for trending: category={}", category);
        }

        Instant now = Instant.now();
        Instant windowStart = now.minus(24, ChronoUnit.HOURS);

        CategoryTopProducts topProducts = interactiveQueries
            .getCategoryTopProducts(category, windowStart, now);

        AtomicInteger rank = new AtomicInteger(1);
        List<TrendingProduct> trending = topProducts.getTopProducts().stream()
            .limit(limit)
            .map(p -> TrendingProduct.builder()
                .rank(rank.getAndIncrement())
                .productId(p.getProductId())
                .title(p.getTitle())
                .merchantId(p.getMerchantId())
                .revenue24h(p.getRevenue())
                .unitsSold24h(p.getUnitsSold())
                .build())
            .collect(Collectors.toList());

        TrendingProductsResponse response = TrendingProductsResponse.builder()
            .category(category)
            .window("24h")
            .generatedAt(now)
            .trendingProducts(trending)
            .servedFrom("ROCKSDB")
            .dataFreshnessSeconds(0)
            .build();

        try {
            redisTemplate.opsForValue().set(cacheKey,
                objectMapper.writeValueAsString(response),
                Duration.ofSeconds(trendingTtl));
        } catch (Exception e) {
            log.warn("Failed to cache trending products: {}", e.getMessage());
        }

        return response;
    }

    // ================================================================
    // ENDPOINT 5 — Merchant Summary
    // ================================================================

    public MerchantSummaryResponse getMerchantSummary(String merchantId) {
        Instant now = Instant.now();
        Instant todayStart = now.truncatedTo(ChronoUnit.DAYS);
        Instant weekStart = now.minus(7, ChronoUnit.DAYS);
        Instant monthStart = now.minus(30, ChronoUnit.DAYS);

        double revenueToday = interactiveQueries.getMerchantRevenue(
            merchantId, "24h", todayStart, now).getTotalRevenue();
        double revenueWeek = interactiveQueries.getMerchantRevenue(
            merchantId, "7d", weekStart, now).getTotalRevenue();

        // Month comes from MongoDB (beyond 7d RocksDB window)
        double revenueMonth = getMonthRevenueFromMongo(merchantId, monthStart);

        // Previous week for comparison
        double revenuePrevWeek = interactiveQueries.getMerchantRevenue(
            merchantId, "7d",
            weekStart.minus(7, ChronoUnit.DAYS), weekStart).getTotalRevenue();
        double vsLastWeekPct = revenuePrevWeek > 0
            ? ((revenueWeek - revenuePrevWeek) / revenuePrevWeek) * 100 : 0.0;

        long ordersToday = interactiveQueries.getMerchantOrdersCreated(merchantId, todayStart, now);
        long cancelled7d = interactiveQueries.getMerchantOrdersCancelled(merchantId, weekStart, now);
        long created7d = interactiveQueries.getMerchantOrdersCreated(merchantId, weekStart, now);
        double cancellationRate7d = created7d > 0 ? (double) cancelled7d / created7d : 0.0;

        // Top products today
        List<CategoryTopProducts.ProductRevenueSummary> topProds =
            interactiveQueries.getMerchantProducts(merchantId, todayStart, now);
        topProds.sort(Comparator.comparingDouble(
            CategoryTopProducts.ProductRevenueSummary::getRevenue).reversed());

        List<TopProductToday> topProductsToday = topProds.stream()
            .limit(3)
            .map(p -> TopProductToday.builder()
                .productId(p.getProductId())
                .title(p.getTitle())
                .units(p.getUnitsSold())
                .revenue(p.getRevenue())
                .build())
            .collect(Collectors.toList());

        // Alerts
        List<AlertItem> alerts = buildAlerts(merchantId, cancellationRate7d, vsLastWeekPct);

        return MerchantSummaryResponse.builder()
            .merchantId(merchantId)
            .generatedAt(now)
            .revenue(SummaryRevenue.builder()
                .today(revenueToday)
                .week(revenueWeek)
                .month(revenueMonth)
                .vsLastWeekPct(vsLastWeekPct)
                .build())
            .orders(SummaryOrders.builder()
                .today(ordersToday)
                .pendingAcknowledgment(0)   // would require Order Service query
                .inFulfillment(0)
                .cancellationRate7d(cancellationRate7d)
                .build())
            .topProductsToday(topProductsToday)
            .alerts(alerts)
            .servedFrom("ROCKSDB+MONGODB")
            .build();
    }

    // ================================================================
    // ENDPOINT 6 — Platform Overview (with Redis cache)
    // ================================================================

    public PlatformOverviewResponse getPlatformOverview(String window) {
        String cacheKey = "platform:overview:" + window;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, PlatformOverviewResponse.class);
            }
        } catch (Exception e) {
            log.warn("Redis cache miss for platform overview");
        }

        Instant now = Instant.now();
        Instant windowStart = windowStart(window, now);

        // Full scan of all merchants in RocksDB
        Map<String, Double> merchantRevenue24h = interactiveQueries.getAllMerchantRevenue(
            now.minus(24, ChronoUnit.HOURS), now);
        Map<String, Double> merchantRevenue7d = interactiveQueries.getAllMerchantRevenue(
            now.minus(7, ChronoUnit.DAYS), now);

        double gmv24h = merchantRevenue24h.values().stream().mapToDouble(Double::doubleValue).sum();
        double gmv7d  = merchantRevenue7d.values().stream().mapToDouble(Double::doubleValue).sum();

        List<MerchantRankItem> topMerchants = merchantRevenue24h.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(10)
            .map(e -> MerchantRankItem.builder()
                .merchantId(e.getKey())
                .revenue24h(e.getValue())
                .rank(0)  // assigned below
                .build())
            .collect(Collectors.toList());

        for (int i = 0; i < topMerchants.size(); i++) {
            topMerchants.get(i).setRank(i + 1);
        }

        PlatformOverviewResponse response = PlatformOverviewResponse.builder()
            .window(window)
            .platform(PlatformMetrics.builder()
                .gmv24h(gmv24h)
                .gmv7d(gmv7d)
                .orders24h(0L)    // from order volume store — wired in future
                .activeMerchants(merchantRevenue24h.size())
                .build())
            .topMerchants(topMerchants)
            .topCategories(List.of())  // product-performance-store scan in future
            .servedFrom("ROCKSDB")
            .build();

        try {
            redisTemplate.opsForValue().set(cacheKey,
                objectMapper.writeValueAsString(response),
                Duration.ofSeconds(platformOverviewTtl));
        } catch (Exception e) {
            log.warn("Failed to cache platform overview: {}", e.getMessage());
        }

        return response;
    }

    // ================================================================
    // Private helpers
    // ================================================================

    private DataSource resolveDataSource(String window) {
        return switch (window) {
            case "1h", "24h", "7d" -> DataSource.ROCKSDB;
            default -> DataSource.MONGODB;
        };
    }

    private Instant windowStart(String window, Instant now) {
        return switch (window) {
            case "1h"  -> now.minus(1, ChronoUnit.HOURS);
            case "24h" -> now.minus(24, ChronoUnit.HOURS);
            case "7d"  -> now.minus(7, ChronoUnit.DAYS);
            case "30d" -> now.minus(30, ChronoUnit.DAYS);
            case "90d" -> now.minus(90, ChronoUnit.DAYS);
            default    -> now.minus(24, ChronoUnit.HOURS);
        };
    }

    private long parseDurationHours(String window) {
        return switch (window) {
            case "1h"  -> 1;
            case "24h" -> 24;
            default    -> 0;
        };
    }

    private long parseDurationDays(String window) {
        return switch (window) {
            case "7d"  -> 7;
            case "30d" -> 30;
            case "90d" -> 90;
            default    -> 0;
        };
    }

    private RevenueMetrics toRevenueMetrics(RevenueAggregate agg) {
        return RevenueMetrics.builder()
            .total(agg.getTotalRevenue())
            .currency(agg.getCurrency() != null ? agg.getCurrency() : "MXN")
            .transactionCount(agg.getTransactionCount())
            .averageOrderValue(agg.getAvgTransaction())
            .minOrderValue(agg.getMinTransaction() == Double.MAX_VALUE ? 0 : agg.getMinTransaction())
            .maxOrderValue(agg.getMaxTransaction())
            .build();
    }

    private ComparisonMetrics buildComparison(double current, double previous) {
        double change = current - previous;
        double changePct = previous > 0 ? (change / previous) * 100 : 0.0;
        String trend = change > 0 ? "UP" : change < 0 ? "DOWN" : "FLAT";
        return ComparisonMetrics.builder()
            .previousPeriodTotal(previous)
            .changeAmount(change)
            .changePct(changePct)
            .trend(trend)
            .build();
    }

    private double getMonthRevenueFromMongo(String merchantId, Instant from) {
        LocalDate fromDate = from.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate toDate = LocalDate.now(ZoneOffset.UTC);
        return revenueSnapshotRepo.findByMerchantAndDateRange(merchantId, fromDate, toDate)
            .stream().mapToDouble(MerchantRevenueSnapshot::getTotalRevenue).sum();
    }

    private RevenueResponse getHistoricalRevenue(String merchantId, String window,
                                                   Instant now, boolean comparePrevious) {
        Instant windowStart = windowStart(window, now);
        LocalDate fromDate = windowStart.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate toDate = now.atZone(ZoneOffset.UTC).toLocalDate();

        double total = revenueSnapshotRepo
            .findByMerchantAndDateRange(merchantId, fromDate, toDate)
            .stream().mapToDouble(MerchantRevenueSnapshot::getTotalRevenue).sum();

        return RevenueResponse.builder()
            .merchantId(merchantId)
            .window(window)
            .windowStart(windowStart)
            .windowEnd(now)
            .revenue(RevenueMetrics.builder().total(total).currency("MXN").build())
            .dataSource("HISTORICAL")
            .servedFrom("MONGODB")
            .build();
    }

    private List<AlertItem> buildAlerts(String merchantId, double cancellationRate7d,
                                         double vsLastWeekPct) {
        List<AlertItem> alerts = new ArrayList<>();

        if (cancellationRate7d > CANCELLATION_ALERT_THRESHOLD) {
            alerts.add(AlertItem.builder()
                .type("CANCELLATION_RATE_HIGH")
                .rate(cancellationRate7d)
                .threshold(CANCELLATION_ALERT_THRESHOLD)
                .build());
        }

        if (vsLastWeekPct < REVENUE_DROP_THRESHOLD) {
            alerts.add(AlertItem.builder()
                .type("REVENUE_DROP")
                .changePct(vsLastWeekPct)
                .build());
        }

        // Low stock alerts come from a Redis set published by Inventory Service events
        try {
            Set<String> lowStock = redisTemplate.opsForSet().members("low-stock:" + merchantId);
            if (lowStock != null) {
                lowStock.forEach(productId -> alerts.add(AlertItem.builder()
                    .type("LOW_STOCK")
                    .productId(productId)
                    .build()));
            }
        } catch (Exception e) {
            log.debug("Could not fetch low-stock alerts for merchantId={}", merchantId);
        }

        return alerts;
    }

    public enum DataSource { ROCKSDB, MONGODB }
}
