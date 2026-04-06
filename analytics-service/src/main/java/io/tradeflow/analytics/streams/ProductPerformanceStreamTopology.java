package io.tradeflow.analytics.streams;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.WindowStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class ProductPerformanceStreamTopology {

    public static final String STORE_PRODUCT_PERFORMANCE = "product-performance-store";
    public static final String STORE_PRODUCT_CATALOG     = "product-catalog-store";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    // Concrete subclass so Serde has a non-generic class to work with
    public static class PaymentMap extends java.util.HashMap<String, Object> {}

    private static final JsonSerde<PaymentMap> PAYMENT_MAP_SERDE =
            new JsonSerde<>(PaymentMap.class);
    private static final JsonSerde<ProductPaymentRecord> PAYMENT_RECORD_SERDE =
            new JsonSerde<>(ProductPaymentRecord.class);
    private static final JsonSerde<EnrichedProductRecord> ENRICHED_RECORD_SERDE =
            new JsonSerde<>(EnrichedProductRecord.class);

    @Autowired
    public void buildTopology(StreamsBuilder builder) {
        log.info("Building Product Performance Stream topology");

        // ── SOURCE 1: Product catalog KTable ──────────────────────────────
        // FIX: Materialized.as() for a KTable needs the 3-type form:
        //   Materialized.<K, V, KeyValueStore<Bytes, byte[]>>as(name)
        // The 2-type form <K, V>as(name) resolves to a WindowStore overload and
        // the compiler cannot resolve the method when only String is passed.
        KTable<String, String> catalogRaw = builder.table(
                "catalog.product-events",
                Consumed.with(Serdes.String(), Serdes.String()),
                Materialized
                        .<String, String, KeyValueStore<Bytes, byte[]>>
                                as(STORE_PRODUCT_CATALOG + "-raw")
                        .withKeySerde(Serdes.String())
                        .withValueSerde(Serdes.String())
        );

        // ── SOURCE 2: Payment events stream ───────────────────────────────
        KStream<String, String> rawPayments = builder.stream(
                "payment.events",
                Consumed.with(Serdes.String(), Serdes.String())
        );

        // Deserialize + filter payment.processed only
        KStream<String, PaymentMap> successfulPayments = rawPayments
                .mapValues(json -> {
                    if (json == null || json.isBlank()) return null;
                    try { return MAPPER.readValue(json, PaymentMap.class); }
                    catch (Exception e) { return null; }
                })
                .filter((k, v) -> v != null &&
                        "payment.processed".equals(v.get("event_type")));

        // ── FLATMAP: payment → per-product records ─────────────────────────
        KStream<String, ProductPaymentRecord> paymentsByProduct = successfulPayments
                .flatMap((orderId, payment) -> {
                    List<KeyValue<String, ProductPaymentRecord>> records = new ArrayList<>();

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> items =
                            (List<Map<String, Object>>) payment.get("items");

                    if (items != null && !items.isEmpty()) {
                        for (Map<String, Object> item : items) {
                            String productId = (String) item.get("product_id");
                            if (productId == null) continue;

                            String merchantId = (String) item.getOrDefault("merchant_id",
                                    payment.getOrDefault("merchant_id",
                                            payment.get("merchantId")));
                            String categoryId =
                                    (String) item.getOrDefault("category_id", "unknown");

                            double revenue = 0.0;
                            try {
                                Object sub = item.get("subtotal");
                                if (sub != null) revenue = Double.parseDouble(sub.toString());
                            } catch (Exception ignored) {}

                            records.add(KeyValue.pair(productId,
                                    new ProductPaymentRecord(
                                            productId,
                                            merchantId != null ? merchantId : "unknown",
                                            categoryId,
                                            revenue)));
                        }
                    } else {
                        // No items list — use product_ids array if present
                        @SuppressWarnings("unchecked")
                        List<String> productIds = (List<String>) payment.get("product_ids");
                        if (productIds != null && !productIds.isEmpty()) {
                            String merchantId = (String) payment.getOrDefault(
                                    "merchant_id", payment.get("merchantId"));
                            double amtEach = 0.0;
                            try {
                                Object amt = payment.get("amount");
                                if (amt != null)
                                    amtEach = Double.parseDouble(amt.toString())
                                            / productIds.size();
                            } catch (Exception ignored) {}
                            final double amtPerProduct = amtEach;
                            for (String pid : productIds) {
                                records.add(KeyValue.pair(pid,
                                        new ProductPaymentRecord(
                                                pid,
                                                merchantId != null ? merchantId : "unknown",
                                                "unknown",
                                                amtPerProduct)));
                            }
                        }
                    }
                    return records;
                });

        // ── JOIN with catalog KTable for enrichment ────────────────────────
        KStream<String, EnrichedProductRecord> enriched = paymentsByProduct
                .leftJoin(
                        catalogRaw,
                        (payment, catalogJson) -> {
                            String title      = "Unknown Product";
                            String categoryId = payment.categoryId();

                            if (catalogJson != null) {
                                try {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> catalog =
                                            MAPPER.readValue(catalogJson, Map.class);
                                    Object t = catalog.get("title");
                                    if (t != null) title = t.toString();
                                    Object c = catalog.get("category_id");
                                    if (c != null) categoryId = c.toString();
                                } catch (Exception ignored) {}
                            }

                            return new EnrichedProductRecord(
                                    payment.productId(),
                                    payment.merchantId(),
                                    title,
                                    categoryId,
                                    payment.revenue());
                        },
                        // FIX: Joined.with needs concrete Serdes — use PAYMENT_RECORD_SERDE
                        // not JsonSerde<ProductPaymentRecord> constructed inline, which
                        // causes the same raw-type ambiguity in some compiler versions.
                        Joined.with(
                                Serdes.String(),
                                PAYMENT_RECORD_SERDE,
                                Serdes.String())
                );

        // ── REKEY by categoryId ────────────────────────────────────────────
        KStream<String, EnrichedProductRecord> byCategory = enriched
                .filter((k, v) -> v != null
                        && v.categoryId() != null
                        && !"unknown".equals(v.categoryId()))
                .selectKey((productId, record) -> record.categoryId());

        // ── AGGREGATE: Top-N products per category, 24h window ────────────
        byCategory
                .groupByKey(Grouped.with(Serdes.String(), ENRICHED_RECORD_SERDE))
                .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofHours(24)))
                .aggregate(
                        CategoryTopProducts::empty,
                        (categoryId, record, agg) -> agg.addProduct(
                                record.productId(),
                                record.merchantId(),
                                record.title(),
                                record.categoryId(),
                                record.revenue()),
                        Materialized
                                .<String, CategoryTopProducts, WindowStore<Bytes, byte[]>>
                                        as(STORE_PRODUCT_PERFORMANCE)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(new JsonSerde<>(CategoryTopProducts.class))
                );

        log.info("Product Performance Stream topology built — store: {}",
                STORE_PRODUCT_PERFORMANCE);
    }

    // ── Internal record types ──────────────────────────────────────────────

    public record ProductPaymentRecord(
            String productId,
            String merchantId,
            String categoryId,
            double revenue) {}

    public record EnrichedProductRecord(
            String productId,
            String merchantId,
            String title,
            String categoryId,
            double revenue) {}
}