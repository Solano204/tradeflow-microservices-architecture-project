package io.tradeflow.analytics.service;

import io.tradeflow.analytics.streams.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.*;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Interactive Queries API service — reads directly from RocksDB state stores.
 *
 * This is the core architectural advantage of Kafka Streams:
 * - No Kafka consumer round-trip
 * - No MongoDB query
 * - No network hop
 * - Just a local RocksDB read taking ~1-5ms
 *
 * The aggregation work was done ONCE at event time.
 * Queries just read the pre-computed result.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InteractiveQueriesService {

    private final StreamsBuilderFactoryBean streamsBuilderFactoryBean;

    // ================================================================
    // REVENUE QUERIES (Topology 1)
    // ================================================================

    /**
     * Fetch revenue aggregate for a merchant over a time window.
     * Used by: GET /analytics/merchants/{id}/revenue
     */
    public RevenueAggregate getMerchantRevenue(String merchantId,
                                                String windowType,
                                                Instant windowStart,
                                                Instant windowEnd) {
        String storeName = resolveRevenueStore(windowType);
        ReadOnlyWindowStore<String, RevenueAggregate> store = getWindowStore(
            storeName, RevenueAggregate.class);

        if (store == null) return RevenueAggregate.empty();

        try (WindowStoreIterator<RevenueAggregate> iter =
                 store.fetch(merchantId, windowStart, windowEnd)) {

            RevenueAggregate total = RevenueAggregate.empty();
            while (iter.hasNext()) {
                KeyValue<Long, RevenueAggregate> entry = iter.next();
                total = RevenueAggregate.merge(total, entry.value);
            }
            return total;
        } catch (Exception e) {
            log.error("Failed to query revenue store for merchantId={}: {}", merchantId, e.getMessage());
            return RevenueAggregate.empty();
        }
    }

    /**
     * Fetch hourly revenue breakdown — list of window-level aggregates.
     */
    public List<Map.Entry<Long, RevenueAggregate>> getMerchantRevenueHourlyBreakdown(
            String merchantId, Instant windowStart, Instant windowEnd) {

        ReadOnlyWindowStore<String, RevenueAggregate> store = getWindowStore(
            RevenueStreamTopology.STORE_HOURLY, RevenueAggregate.class);

        if (store == null) return List.of();

        List<Map.Entry<Long, RevenueAggregate>> result = new ArrayList<>();
        try (WindowStoreIterator<RevenueAggregate> iter =
                 store.fetch(merchantId, windowStart, windowEnd)) {
            while (iter.hasNext()) {
                KeyValue<Long, RevenueAggregate> entry = iter.next();
                result.add(Map.entry(entry.key, entry.value));
            }
        } catch (Exception e) {
            log.error("Failed to query hourly breakdown: merchantId={}", merchantId, e);
        }
        return result;
    }

    /**
     * Platform-wide GMV: scan ALL merchant keys in the daily store.
     * More expensive — cached aggressively in Redis (5-min TTL).
     */
    public Map<String, Double> getAllMerchantRevenue(Instant windowStart, Instant windowEnd) {
        ReadOnlyWindowStore<String, RevenueAggregate> store = getWindowStore(
            RevenueStreamTopology.STORE_DAILY, RevenueAggregate.class);

        if (store == null) return Map.of();

        Map<String, Double> revenueByMerchant = new HashMap<>();
        try (KeyValueIterator<Windowed<String>, RevenueAggregate> iter =
                 store.fetchAll(windowStart, windowEnd)) {
            while (iter.hasNext()) {
                KeyValue<Windowed<String>, RevenueAggregate> entry = iter.next();
                revenueByMerchant.merge(entry.key.key(),
                    entry.value.getTotalRevenue(), Double::sum);
            }
        } catch (Exception e) {
            log.error("Failed to scan all merchant revenue: {}", e.getMessage());
        }
        return revenueByMerchant;
    }

    // ================================================================
    // ORDER VOLUME QUERIES (Topology 2)
    // ================================================================

    /**
     * Sum of created orders for a merchant over a window.
     */
    public long getMerchantOrdersCreated(String merchantId,
                                          Instant windowStart, Instant windowEnd) {
        return sumWindowStore(OrderVolumeStreamTopology.STORE_CREATED_HOURLY,
            merchantId, windowStart, windowEnd);
    }

    /**
     * Sum of cancelled orders for a merchant over a window.
     */
    public long getMerchantOrdersCancelled(String merchantId,
                                            Instant windowStart, Instant windowEnd) {
        return sumWindowStore(OrderVolumeStreamTopology.STORE_CANCELLED_HOURLY,
            merchantId, windowStart, windowEnd);
    }

    // ================================================================
    // PRODUCT PERFORMANCE QUERIES (Topology 3)
    // ================================================================

    /**
     * Top products for a specific category — the trending endpoint.
     * Single key lookup — extremely fast.
     */
    public CategoryTopProducts getCategoryTopProducts(String categoryId,
                                                       Instant windowStart,
                                                       Instant windowEnd) {
        ReadOnlyWindowStore<String, CategoryTopProducts> store = getWindowStore(
            ProductPerformanceStreamTopology.STORE_PRODUCT_PERFORMANCE,
            CategoryTopProducts.class);

        if (store == null) return CategoryTopProducts.empty();

        try (WindowStoreIterator<CategoryTopProducts> iter =
                 store.fetch(categoryId, windowStart, windowEnd)) {

            CategoryTopProducts latest = CategoryTopProducts.empty();
            while (iter.hasNext()) {
                latest = iter.next().value;  // Take the most recent window
            }
            return latest;
        } catch (Exception e) {
            log.error("Failed to query product performance for categoryId={}: {}", categoryId, e.getMessage());
            return CategoryTopProducts.empty();
        }
    }

    /**
     * All products for a specific merchant — scans all categories, filters by merchantId.
     * Used by GET /analytics/merchants/{id}/products
     */
    public List<CategoryTopProducts.ProductRevenueSummary> getMerchantProducts(
            String merchantId, Instant windowStart, Instant windowEnd) {

        ReadOnlyWindowStore<String, CategoryTopProducts> store = getWindowStore(
            ProductPerformanceStreamTopology.STORE_PRODUCT_PERFORMANCE,
            CategoryTopProducts.class);

        if (store == null) return List.of();

        List<CategoryTopProducts.ProductRevenueSummary> result = new ArrayList<>();
        try (KeyValueIterator<Windowed<String>, CategoryTopProducts> iter =
                 store.fetchAll(windowStart, windowEnd)) {
            while (iter.hasNext()) {
                KeyValue<Windowed<String>, CategoryTopProducts> entry = iter.next();
                if (entry.value != null && entry.value.getTopProducts() != null) {
                    entry.value.getTopProducts().stream()
                        .filter(p -> merchantId.equals(p.getMerchantId()))
                        .forEach(result::add);
                }
            }
        } catch (Exception e) {
            log.error("Failed to scan merchant products for merchantId={}: {}", merchantId, e.getMessage());
        }
        return result;
    }

    // ================================================================
    // STREAMS HEALTH
    // ================================================================

    /**
     * Check if Kafka Streams is in a running/queryable state.
     */
    public KafkaStreams.State getStreamsState() {
        KafkaStreams streams = getStreams();
        return streams != null ? streams.state() : KafkaStreams.State.NOT_RUNNING;
    }

    public boolean isReady() {
        KafkaStreams.State state = getStreamsState();
        return state == KafkaStreams.State.RUNNING ||
               state == KafkaStreams.State.REBALANCING;
    }

    // ================================================================
    // Private helpers
    // ================================================================

    @SuppressWarnings("unchecked")
    private <V> ReadOnlyWindowStore<String, V> getWindowStore(String storeName,
                                                                Class<V> valueType) {
        try {
            KafkaStreams streams = getStreams();
            if (streams == null || !isReady()) {
                log.warn("Kafka Streams not ready for store query: {}", storeName);
                return null;
            }
            return streams.store(StoreQueryParameters.fromNameAndType(
                storeName, QueryableStoreTypes.windowStore()));
        } catch (InvalidStateStoreException e) {
            log.warn("Store {} not yet available (streams may be rebalancing): {}",
                storeName, e.getMessage());
            return null;
        }
    }

    private long sumWindowStore(String storeName, String key,
                                  Instant start, Instant end) {
        ReadOnlyWindowStore<String, Long> store = getWindowStore(storeName, Long.class);
        if (store == null) return 0L;
        long total = 0L;
        try (WindowStoreIterator<Long> iter = store.fetch(key, start, end)) {
            while (iter.hasNext()) {
                Long val = iter.next().value;
                if (val != null) total += val;
            }
        } catch (Exception e) {
            log.error("Failed to sum window store {}: {}", storeName, e.getMessage());
        }
        return total;
    }

    private String resolveRevenueStore(String windowType) {
        return switch (windowType) {
            case "1h"  -> RevenueStreamTopology.STORE_HOURLY;
            case "24h" -> RevenueStreamTopology.STORE_DAILY;
            case "7d"  -> RevenueStreamTopology.STORE_WEEKLY;
            default    -> RevenueStreamTopology.STORE_DAILY;
        };
    }

    private KafkaStreams getStreams() {
        try {
            return streamsBuilderFactoryBean.getKafkaStreams();
        } catch (Exception e) {
            log.warn("Failed to get KafkaStreams instance: {}", e.getMessage());
            return null;
        }
    }
}
