package io.tradeflow.analytics.serde;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.tradeflow.analytics.dto.KafkaEventDtos;
import io.tradeflow.analytics.streams.CategoryTopProducts;
import io.tradeflow.analytics.streams.OrderVolumeAggregate;
import io.tradeflow.analytics.streams.RevenueAggregate;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Custom JSON Serdes for Kafka Streams.
 *
 * Each aggregate type stored in RocksDB needs a Serde so Kafka Streams
 * can serialize/deserialize state to/from disk and changelog topics.
 */
public class AnalyticsSerdes {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    // ===== Revenue Aggregate Serde =====
    public static Serde<RevenueAggregate> revenueAggregate() {
        return new JsonSerde<>(RevenueAggregate.class);
    }

    // ===== OrderVolume Aggregate Serde =====
    public static Serde<OrderVolumeAggregate> orderVolumeAggregate() {
        return new JsonSerde<>(OrderVolumeAggregate.class);
    }

    // ===== CategoryTopProducts Serde =====
    public static Serde<CategoryTopProducts> categoryTopProducts() {
        return new JsonSerde<>(CategoryTopProducts.class);
    }

    // ===== PaymentProcessedEvent Serde =====
    public static Serde<KafkaEventDtos.PaymentProcessedEvent> paymentEvent() {
        return new JsonSerde<>(KafkaEventDtos.PaymentProcessedEvent.class);
    }

    // ===== OrderCreatedEvent Serde =====
    public static Serde<KafkaEventDtos.OrderCreatedEvent> orderCreatedEvent() {
        return new JsonSerde<>(KafkaEventDtos.OrderCreatedEvent.class);
    }

    // ===== OrderCancelledEvent Serde =====
    public static Serde<KafkaEventDtos.OrderCancelledEvent> orderCancelledEvent() {
        return new JsonSerde<>(KafkaEventDtos.OrderCancelledEvent.class);
    }

    // ===== ProductCatalogUpdated Serde =====
    public static Serde<KafkaEventDtos.ProductCatalogUpdatedEvent> productCatalogEvent() {
        return new JsonSerde<>(KafkaEventDtos.ProductCatalogUpdatedEvent.class);
    }

    // ===== Generic JSON Serde =====
    public static class JsonSerde<T> implements Serde<T> {

        private final Class<T> targetType;

        public JsonSerde(Class<T> targetType) {
            this.targetType = targetType;
        }

        @Override
        public Serializer<T> serializer() {
            return (topic, data) -> {
                if (data == null) return null;
                try {
                    return MAPPER.writeValueAsBytes(data);
                } catch (Exception e) {
                    throw new RuntimeException("Serialization failed for " + targetType.getSimpleName(), e);
                }
            };
        }

        @Override
        public Deserializer<T> deserializer() {
            return (topic, data) -> {
                if (data == null) return null;
                try {
                    return MAPPER.readValue(data, targetType);
                } catch (Exception e) {
                    throw new RuntimeException("Deserialization failed for " + targetType.getSimpleName(), e);
                }
            };
        }
    }
}
