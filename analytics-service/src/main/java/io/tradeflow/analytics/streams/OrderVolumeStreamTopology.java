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

/**
 * TOPOLOGY 2 — Order Volume Stream
 *
 * Input topic: "order.events"
 *   Published by Order Service OutboxRelayService.
 *   event_type values we care about:
 *     "event.order-paid"      → count as CREATED (order went through to payment)
 *     "event.order-cancelled" → count as CANCELLED
 *     "event.order-rejected"  → count as CANCELLED (fraud rejection)
 *
 * NOTE: There is NO standalone "order.created" Kafka topic published by Order Service.
 * Order Service publishes to "order.events" with different event_type values.
 * The cmd.* topics are internal SAGA commands, not domain events for analytics.
 *
 * Output stores (keyed by merchantId):
 *   "orders-created-hourly-store"   — 1h tumbling count of created orders
 *   "orders-cancelled-hourly-store" — 1h tumbling count of cancelled/rejected orders
 *
 * HTTP endpoints:
 *   GET /analytics/merchants/{id}/orders?window=24h
 */
@Component
@Slf4j
public class OrderVolumeStreamTopology {

    public static final String STORE_CREATED_HOURLY   = "orders-created-hourly-store";
    public static final String STORE_CANCELLED_HOURLY = "orders-cancelled-hourly-store";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Autowired
    public void buildTopology(StreamsBuilder builder) {
        log.info("Building Order Volume Stream topology — consuming 'order.events'");

        // SOURCE: order.events — plain string values
        KStream<String, String> rawOrderEvents = builder.stream(
                "order.events",
                Consumed.with(Serdes.String(), Serdes.String())
        );

        // DESERIALIZE
        KStream<String, Map<String, Object>> orderEvents = rawOrderEvents
                .mapValues(json -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = MAPPER.readValue(json, Map.class);
                        return m;
                    } catch (Exception e) {
                        log.warn("Could not parse order event JSON: {}", e.getMessage());
                        return null;
                    }
                })
                .filter((k, v) -> v != null && v.get("merchant_id") != null);

        // REKEY by merchantId
        KStream<String, Map<String, Object>> byMerchant = orderEvents
                .selectKey((k, v) -> (String) v.get("merchant_id"));

        // FILTER: order created / paid events
        // "event.order-paid" = order completed payment = a successful order was created
        KStream<String, Map<String, Object>> createdStream = byMerchant
                .filter((merchantId, event) -> {
                    String et = (String) event.get("event_type");
                    return "event.order-paid".equals(et);
                });

        // FILTER: order cancelled / rejected events
        KStream<String, Map<String, Object>> cancelledStream = byMerchant
                .filter((merchantId, event) -> {
                    String et = (String) event.get("event_type");
                    return "event.order-cancelled".equals(et) || "event.order-rejected".equals(et);
                });

        // ==== AGGREGATE: Created orders per merchant per hour ====
        createdStream
                .mapValues(v -> 1L)
                .groupByKey(Grouped.with(Serdes.String(), Serdes.Long()))
                .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofHours(1)))
                .reduce(Long::sum,
                        Materialized.<String, Long, WindowStore<Bytes, byte[]>>
                                        as(STORE_CREATED_HOURLY)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(Serdes.Long())
                );

        // ==== AGGREGATE: Cancelled orders per merchant per hour ====
        cancelledStream
                .mapValues(v -> 1L)
                .groupByKey(Grouped.with(Serdes.String(), Serdes.Long()))
                .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofHours(1)))
                .reduce(Long::sum,
                        Materialized.<String, Long, WindowStore<Bytes, byte[]>>
                                        as(STORE_CANCELLED_HOURLY)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(Serdes.Long())
                );

        log.info("Order Volume Stream topology built — stores: {}, {}",
                STORE_CREATED_HOURLY, STORE_CANCELLED_HOURLY);
    }
}