package io.tradeflow.inventory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * InfrastructureConfig — Inventory Service
 *
 * TRACING APPROACH — Micrometer auto-instrumentation (no manual interceptors)
 *
 * Spring Boot 3.2.x auto-configures Kafka tracing when these are on the classpath:
 *   - micrometer-tracing-bridge-brave
 *   - zipkin-reporter-brave
 *   - spring-kafka
 *
 * Spring Boot's KafkaAutoConfiguration detects micrometer-tracing and wraps the
 * ProducerFactory and ConsumerFactory automatically via MicrometerConsumerListener
 * and MicrometerProducerListener. This propagates traceId/spanId through Kafka
 * headers without requiring TracingProducerInterceptor or TracingConsumerInterceptor
 * to be declared manually — those are a Sleuth-era pattern that requires
 * brave-instrumentation-kafka-clients on the classpath explicitly.
 *
 * The result in Zipkin is identical: inventory-service appears as a service,
 * spans are linked across HTTP → Kafka → inventory → Kafka → downstream.
 *
 * KAFKA CONSUMER DESERIALIZER FIXES (unchanged):
 *
 * 1. USE_TYPE_INFO_HEADERS = false
 *    Order Service publishes without Spring __TypeId__ headers.
 *
 * 2. VALUE_DEFAULT_TYPE = "java.util.HashMap"
 *    Default target when no type header is present.
 *
 * 3. ErrorHandlingDeserializer wrapping JsonDeserializer
 *    Prevents a single bad message from crashing the consumer thread.
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
    // PRODUCER — publishes to inventory.events
    //
    // No interceptor declared here. Spring Boot auto-wraps this factory with
    // MicrometerProducerListener when micrometer-tracing-bridge-brave is on
    // the classpath — trace context is injected into Kafka headers automatically.
    // ─────────────────────────────────────────────────────────────────────────

    @Bean
    ProducerFactory<String, Map<String, Object>> producerFactory() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,      kafkaBootstrapServers);
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        cfg.put(ProducerConfig.ACKS_CONFIG,                   "all");
        cfg.put(ProducerConfig.RETRIES_CONFIG,                3);
        cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,     true);
        cfg.put(JsonSerializer.ADD_TYPE_INFO_HEADERS,         false);
        return new DefaultKafkaProducerFactory<>(cfg);
    }

    @Bean
    KafkaTemplate<String, Map<String, Object>> kafkaTemplate(
            ProducerFactory<String, Map<String, Object>> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONSUMER — accepts plain JSON without Spring type headers
    //
    // Topics consumed:
    //   cmd.reserve-inventory   — Order Service (no type headers)
    //   cmd.confirm-inventory   — Order Service (no type headers)
    //   catalog.product-events  — Catalog Service (no type headers)
    //
    // No interceptor declared here. Spring Boot auto-wraps this factory with
    // MicrometerConsumerListener — extracts trace context from inbound Kafka
    // headers so inventory spans become children of the originating trace.
    // ─────────────────────────────────────────────────────────────────────────

    @Bean
    ConsumerFactory<String, Map<String, Object>> consumerFactory() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,  kafkaBootstrapServers);
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,  "earliest");
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // ── Key deserializer ─────────────────────────────────────────────────
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                ErrorHandlingDeserializer.class);
        cfg.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS,
                StringDeserializer.class);

        // ── Value deserializer ───────────────────────────────────────────────
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ErrorHandlingDeserializer.class);
        cfg.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS,
                JsonDeserializer.class);

        // CRITICAL FIX 1: do NOT require Spring __TypeId__ header
        cfg.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        // CRITICAL FIX 2: default target type = Map
        cfg.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "java.util.HashMap");

        cfg.put(JsonDeserializer.TRUSTED_PACKAGES, "*");

        return new DefaultKafkaConsumerFactory<>(cfg);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, Map<String, Object>> kafkaListenerContainerFactory(
            ConsumerFactory<String, Map<String, Object>> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Map<String, Object>>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setConcurrency(3);
        return factory;
    }

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
}