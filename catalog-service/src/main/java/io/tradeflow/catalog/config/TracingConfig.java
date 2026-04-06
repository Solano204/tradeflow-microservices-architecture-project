package io.tradeflow.catalog.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import io.micrometer.tracing.Tracer;
import net.ttddyy.observation.boot.autoconfigure.DataSourceObservationAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.Map;

/**
 * TracingConfig — wires distributed tracing for the Catalog Service.
 *
 * What this configures:
 * 1. ObservedAspect     → enables @Observed on any bean method (manual span creation via annotation)
 * 2. KafkaListenerContainerFactory → CRITICAL: enables observation on every Kafka consumer
 *    so the traceId from the Kafka message headers is EXTRACTED and becomes the current span.
 *    Without this, Kafka consumer spans are orphaned (new traceId instead of continuing the producer's).
 * 3. JDBC tracing       → handled automatically by datasource-micrometer-spring-boot dependency
 * 4. MongoDB tracing    → handled automatically by Spring Data MongoDB + Micrometer auto-config
 * 5. Redis tracing      → handled automatically by Lettuce + Micrometer auto-config
 *
 * You do NOT need to configure anything for:
 * - Inbound HTTP spans  → Spring MVC auto-creates them via ObservationFilter
 * - Outbound HTTP spans → RestTemplate/WebClient auto-propagate via Micrometer
 * - Kafka producer spans → KafkaTemplate auto-propagates traceId into headers
 */
@Configuration
@ImportAutoConfiguration(DataSourceObservationAutoConfiguration.class)
public class TracingConfig {

    /**
     * Enables the @Observed annotation on any Spring bean method.
     * Usage: @Observed(name = "catalog.createProduct", contextualName = "create-product")
     * Creates a child span automatically for that method call.
     */
    @Bean
    ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }

    /**
     * CRITICAL — Kafka Consumer tracing.
     *
     * This overrides the default kafkaListenerContainerFactory bean to enable
     * micrometer observation. When a message arrives, Micrometer reads the
     * "traceparent" (W3C) or "b3" header from the Kafka message and CONTINUES
     * the same trace that the producer started.
     *
     * Result: producer traceId === consumer traceId === same trace in Zipkin.
     *
     * Without this bean: every Kafka consumer creates a NEW root span (new traceId).
     * The trace chain breaks and you can't follow a request across service boundaries.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, Map<String, Object>> kafkaListenerContainerFactory(
            ConsumerFactory<String, Map<String, Object>> consumerFactory,
            ObservationRegistry observationRegistry) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, Map<String, Object>>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setConcurrency(3);

        // ✅ THIS IS THE KEY LINE — enables traceId extraction from Kafka headers
        factory.getContainerProperties().setObservationEnabled(true);

        return factory;
    }
}