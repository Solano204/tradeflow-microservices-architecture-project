package io.tradeflow.analytics.streams;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;

import java.util.Map;

/**
 * Serializer/Deserializer for raw Map<String, Object> Kafka payloads.
 * Used when receiving events from other services without a known schema class.
 * Allows the stream topologies to work with any event shape using get() calls.
 */
@SuppressWarnings("unchecked")
public class MapSerializer implements Serializer<Map<String, Object>> {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    @Override
    public byte[] serialize(String topic, Map<String, Object> data) {
        if (data == null) return null;
        try { return MAPPER.writeValueAsBytes(data); }
        catch (Exception e) { throw new RuntimeException("Serialization error", e); }
    }
}

