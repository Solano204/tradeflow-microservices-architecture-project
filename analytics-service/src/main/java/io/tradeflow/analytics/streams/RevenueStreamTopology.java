package io.tradeflow.analytics.streams;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.WindowStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

@Component
@Slf4j
public class RevenueStreamTopology {

    public static final String STORE_HOURLY = "revenue-hourly-store";
    public static final String STORE_DAILY  = "revenue-daily-store";
    public static final String STORE_WEEKLY = "revenue-weekly-store";
    public static final String DAILY_STORE  = STORE_DAILY;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    // FIX: Serde for Map<String,Object> — avoids raw Map.class type error.
    // JsonSerde<Map<String,Object>> is defined as a generic class in JsonSerde.java,
    // but since Java erases generics at runtime we use a dedicated subclass here
    // so Kafka Streams gets a concrete class to instantiate.
    private static final JsonSerde<PaymentMap> PAYMENT_MAP_SERDE =
            new JsonSerde<>(PaymentMap.class);

    // Thin wrapper so we have a concrete class for the Serde
    public static class PaymentMap extends java.util.HashMap<String, Object> {}

    @Autowired
    public void buildTopology(StreamsBuilder builder) {
        log.info("Building Revenue Stream topology — consuming 'payment.events'");

        // SOURCE: raw String values — we control deserialization to avoid
        // Quarkus SmallRye plain-JSON vs Spring __TypeId__ header mismatch
        KStream<String, String> rawPayments = builder.stream(
                "payment.events",
                Consumed.with(Serdes.String(), Serdes.String())
        );

        // DESERIALIZE + FILTER: keep only payment.processed events
        KStream<String, PaymentMap> payments = rawPayments
                .mapValues(json -> {
                    if (json == null || json.isBlank()) return null;
                    try {
                        PaymentMap m = MAPPER.readValue(json, PaymentMap.class);
                        return m;
                    } catch (Exception e) {
                        log.warn("Revenue topology: could not parse JSON: {}", e.getMessage());
                        return null;
                    }
                })
                .filter((k, v) -> v != null &&
                        "payment.processed".equals(v.get("event_type")));

        // REKEY: Kafka key (orderId) → merchantId for per-merchant aggregation
        KStream<String, PaymentMap> byMerchant = payments
                .selectKey((orderId, event) -> {
                    String mid = (String) event.get("merchant_id");
                    if (mid == null) mid = (String) event.get("merchantId");
                    return mid != null ? mid : "unknown";
                })
                .filter((merchantId, event) -> !"unknown".equals(merchantId));

        // Reusable grouping — concrete Serde avoids the raw-Map compile error
        KGroupedStream<String, PaymentMap> grouped =
                byMerchant.groupByKey(
                        Grouped.with(Serdes.String(), PAYMENT_MAP_SERDE));

        // ==== AGGREGATE 1: 1-hour tumbling window ====
        grouped
                .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofHours(1)))
                .aggregate(
                        RevenueAggregate::empty,
                        (merchantId, payment, agg) -> agg.add(extractAmount(payment)),
                        Materialized
                                .<String, RevenueAggregate, WindowStore<Bytes, byte[]>>
                                        as(STORE_HOURLY)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(new JsonSerde<>(RevenueAggregate.class))
                );

        // ==== AGGREGATE 2: 24-hour tumbling window ====
        grouped
                .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofHours(24)))
                .aggregate(
                        RevenueAggregate::empty,
                        (merchantId, payment, agg) -> agg.add(extractAmount(payment)),
                        Materialized
                                .<String, RevenueAggregate, WindowStore<Bytes, byte[]>>
                                        as(STORE_DAILY)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(new JsonSerde<>(RevenueAggregate.class))
                );

        // ==== AGGREGATE 3: 7-day tumbling window ====
        grouped
                .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofDays(7)))
                .aggregate(
                        RevenueAggregate::empty,
                        (merchantId, payment, agg) -> agg.add(extractAmount(payment)),
                        Materialized
                                .<String, RevenueAggregate, WindowStore<Bytes, byte[]>>
                                        as(STORE_WEEKLY)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(new JsonSerde<>(RevenueAggregate.class))
                );

        log.info("Revenue Stream topology built — stores: {}, {}, {}",
                STORE_HOURLY, STORE_DAILY, STORE_WEEKLY);
    }

    private static double extractAmount(Map<String, Object> event) {
        Object raw = event.get("amount");
        if (raw == null) return 0.0;
        try { return Double.parseDouble(raw.toString()); }
        catch (Exception e) { return 0.0; }
    }
}