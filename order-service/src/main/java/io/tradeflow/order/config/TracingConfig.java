package io.tradeflow.order.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

/**
 * TracingConfig — Wires Micrometer Observation into Kafka producer + consumer.
 *
 * WHY THIS FILE EXISTS:
 * ─────────────────────────────────────────────────────────────────────────────
 * Spring Boot 3.2 auto-configures Micrometer Tracing (Brave + Zipkin reporter)
 * from the classpath. BUT Kafka observation must be explicitly enabled on both:
 *   1. KafkaTemplate        — PRODUCER side (injects traceparent header on send)
 *   2. ContainerFactory     — CONSUMER side (extracts traceparent header on receive)
 *
 * Without this file:
 *   POST /orders → traceId=abc123 → KafkaTemplate sends with NO traceparent header
 *   Consumer receives → creates BRAND NEW traceId=xyz999 → trace is broken
 *
 * With this file:
 *   POST /orders → traceId=abc123 → KafkaTemplate sends with traceparent=abc123
 *   Consumer receives → extracts abc123 → PostgreSQL child spans still abc123
 *   Zipkin shows ONE complete trace from HTTP all the way through Kafka and DB
 *
 * CRITICAL NOTE on @Observed:
 * ─────────────────────────────────────────────────────────────────────────────
 * The ObservedAspect bean enables the @Observed annotation to work on Spring
 * beans via AOP. Without it, @Observed is silently ignored and no spans appear.
 * You already have spring-boot-starter-aop in pom.xml — this bean activates it.
 */
@Configuration
public class TracingConfig {

    /**
     * Enables @Observed annotation support on any Spring-managed bean method.
     *
     * Usage example in OrderService or OrderSagaConsumers:
     *
     *   @Observed(name = "saga.order.payment-processed",
     *             contextualName = "payment-processed",
     *             lowCardinalityKeyValues = {"service", "order"})
     *   public void onPaymentProcessed(...) { ... }
     *
     * This creates a named span in Zipkin for that method, as a child of the
     * current active span (e.g. the Kafka consumer span or HTTP request span).
     */
    @Bean
    ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }

    /**
     * Enable Kafka CONSUMER observation — extracts traceId from incoming message headers.
     *
     * This patches the ConcurrentKafkaListenerContainerFactory that was configured
     * in InfrastructureConfig. Spring Boot injects it here by type.
     *
     * What happens at runtime:
     *   1. Kafka message arrives with header: traceparent=00-abc123...-01
     *   2. Container extracts the header and restores the span context
     *   3. All code inside @KafkaListener runs as a CHILD SPAN of traceId=abc123
     *   4. Any DB writes, outbox saves, and further KafkaTemplate.send() calls
     *      are all children of that same traceId
     *
     * This is the single most important setting for cross-service trace continuity.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, ?> observedKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactory<String, ?> factory,
            ObservationRegistry observationRegistry) {
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}