package io.tradeflow.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.observation.ObservationRegistry;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.web.reactive.function.client.WebClient;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * InfrastructureConfig
 *
 * TRACING CHANGES vs original:
 * ─────────────────────────────────────────────────────────────────────────────
 * kafkaTemplate() now accepts ObservationRegistry and calls:
 *   template.setObservationEnabled(true)
 *
 * This is what makes the PRODUCER inject the W3C "traceparent" header into
 * every Kafka message that OutboxRelayService sends. Without this one line,
 * the traceId is never written into the Kafka message headers and every
 * downstream consumer starts a fresh trace — the chain is broken.
 *
 * The CONSUMER side observation is enabled in TracingConfig.java.
 *
 * ORIGINAL FIXES (unchanged):
 * ─────────────────────────────────────────────────────────────────────────────
 * Kafka consumer deserializer fix for Payment Service (Quarkus/SmallRye):
 *   USE_TYPE_INFO_HEADERS = false   — no Spring type headers in Quarkus payloads
 *   VALUE_DEFAULT_TYPE = HashMap    — all SAGA consumers use Map<String,Object>
 *   ErrorHandlingDeserializer       — bad messages are skipped, not crash-looping
 */
@Configuration
public class InfrastructureConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String kafkaBootstrapServers;

    @Bean
    TomcatProtocolHandlerCustomizer<?> virtualThreadsExecutor() {
        return p -> p.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRODUCER — publishes Map<String,Object>
    //
    // ADD_TYPE_INFO_HEADERS = false  — do NOT embed Spring type headers in
    //   outgoing messages. Quarkus consumers, Fraud Service, etc. don't use them.
    // ─────────────────────────────────────────────────────────────────────────

    @Bean
    ProducerFactory<String, Map<String, Object>> producerFactory() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,  kafkaBootstrapServers);
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        cfg.put(ProducerConfig.ACKS_CONFIG,               "all");
        cfg.put(ProducerConfig.RETRIES_CONFIG,            3);
        cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        cfg.put(JsonSerializer.ADD_TYPE_INFO_HEADERS,     false);
        return new DefaultKafkaProducerFactory<>(cfg);
    }

    /**
     * TRACING CHANGE: accepts ObservationRegistry and enables observation.
     *
     * template.setObservationEnabled(true) is the single line that activates
     * W3C traceparent header injection on every kafkaTemplate.send() call.
     *
     * At runtime when OutboxRelayService calls:
     *   kafkaTemplate.send("cmd.reserve-inventory", orderId, payload)
     *
     * Spring automatically adds to the Kafka message headers:
     *   traceparent: 00-abc123...-spanId-01
     *
     * The inventory-service consumer reads that header and continues the trace.
     */
    @Bean
    KafkaTemplate<String, Map<String, Object>> kafkaTemplate(
            ObservationRegistry observationRegistry) {
        KafkaTemplate<String, Map<String, Object>> template =
                new KafkaTemplate<>(producerFactory());
        // CRITICAL: this is what injects traceparent into every Kafka message header
        template.setObservationEnabled(true);
        return template;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONSUMER — accepts plain JSON without Spring type headers
    //
    // Topics consumed:
    //   inventory.events  — published by Inventory Service (Spring, no type headers)
    //   fraud.events      — published by Fraud Service (Spring, no type headers)
    //   payment.events    — published by Payment Service (Quarkus SmallRye, no type headers)
    // ─────────────────────────────────────────────────────────────────────────

    @Bean
    ConsumerFactory<String, Map<String, Object>> consumerFactory() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,  kafkaBootstrapServers);
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,  "earliest");
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // Key deserializer
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                ErrorHandlingDeserializer.class);
        cfg.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS,
                StringDeserializer.class);

        // Value deserializer — wrapped in ErrorHandlingDeserializer so bad
        // messages are logged and skipped instead of crash-looping the consumer
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ErrorHandlingDeserializer.class);
        cfg.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS,
                JsonDeserializer.class);

        // CRITICAL FIX 1: do NOT require Spring __TypeId__ headers
        // Payment Service (Quarkus) publishes plain JSON without them
        cfg.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        // CRITICAL FIX 2: always deserialize into HashMap
        // All SAGA consumers use Map<String,Object> — this is correct
        cfg.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "java.util.HashMap");

        // Trust all packages — we're deserializing into HashMap anyway
        cfg.put(JsonDeserializer.TRUSTED_PACKAGES, "*");

        return new DefaultKafkaConsumerFactory<>(cfg);
    }

    /**
     * NOTE: observation (tracing) on the consumer container is enabled in
     * TracingConfig.observedKafkaListenerContainerFactory() which patches
     * this factory after construction. Do NOT duplicate it here.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, Map<String, Object>> kafkaListenerContainerFactory() {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Map<String, Object>>();
        factory.setConsumerFactory(consumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setConcurrency(3);
        return factory;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Other infrastructure beans — unchanged
    // ─────────────────────────────────────────────────────────────────────────

    @Bean
    LockProvider lockProvider(DataSource ds) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(ds))
                        .usingDbTime()
                        .build());
    }

    @Bean
    ObjectMapper objectMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return m;
    }

    @Bean
    WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}