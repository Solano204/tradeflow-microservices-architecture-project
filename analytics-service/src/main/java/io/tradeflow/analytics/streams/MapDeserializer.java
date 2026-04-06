package io.tradeflow.analytics.streams;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.common.serialization.Deserializer;

import java.util.Map;

public class MapDeserializer implements Deserializer<Map<String, Object>> {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> deserialize(String topic, byte[] data) {
        if (data == null) return null;
        try { return MAPPER.readValue(data, Map.class); }
        catch (Exception e) {
            // Malformed event — return empty map rather than crashing the topology
            return Map.of();
        }
    }
}
