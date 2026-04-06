package io.tradeflow.search;

import brave.Tracing;
import brave.kafka.clients.KafkaTracing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TracingConfig — Kafka tracing wiring for Brave + Zipkin.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHAT WAS REMOVED AND WHY:
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * REMOVED: URLConnectionSender bean
 *   Spring Boot 3.x auto-configures this from:
 *     management.zipkin.tracing.endpoint=http://zipkin:9411/api/v2/spans
 *   Declaring it manually creates a SECOND sender → spans reported twice,
 *   or Spring Boot's ZipkinAutoConfiguration backs off entirely → no spans
 *   reach Zipkin → service never appears in the UI.
 *
 * REMOVED: AsyncReporter<Span> bean
 *   Spring Boot auto-configures this via ZipkinAutoConfiguration.
 *   Manual declaration causes the same backoff problem as above.
 *
 * REMOVED: ZipkinSpanHandler bean
 *   Same reason. Spring Boot wires Brave → ZipkinSpanHandler → AsyncReporter
 *   → URLConnectionSender automatically. Declaring it again creates a
 *   duplicate handler that confuses the span pipeline.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHAT SPRING BOOT AUTO-CONFIGURES (zero manual beans needed):
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   Brave Tracing         ← BraveAutoConfiguration
 *                            (triggered by micrometer-tracing-bridge-brave)
 *
 *   URLConnectionSender   ← ZipkinAutoConfiguration
 *   AsyncReporter         ← ZipkinAutoConfiguration
 *   ZipkinSpanHandler     ← ZipkinAutoConfiguration
 *                            (all triggered by zipkin-reporter-brave +
 *                             management.zipkin.tracing.endpoint property)
 *
 *   HTTP inbound tracing  ← Spring MVC Actuator auto-config
 *   HTTP outbound tracing ← ObservationWebClientCustomizer on WebClient.Builder
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHAT STILL NEEDS A MANUAL BEAN (kept below):
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   KafkaTracing — Spring Boot does NOT auto-wrap your Producer/Consumer.
 *   The brave-instrumentation-kafka-clients artifact provides the
 *   KafkaTracing utility class, but YOU must:
 *     1. Declare the KafkaTracing bean (done here)
 *     2. Use it in InfrastructureConfig to wrap createKafkaProducer()
 *        and createKafkaConsumer() — see InfrastructureConfig.java
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * HOW THE TRACE FLOWS END-TO-END:
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   GET /search/products arrives
 *   → Spring MVC auto-creates root span: traceId=abc123, spanId=001
 *
 *   → kafkaTemplate.send("catalog.product-events", payload)
 *      TracingProducer (from InfrastructureConfig) injects header:
 *        traceparent: 00-abc123-002-01
 *
 *   → SearchIndexConsumers.onCatalogEvent() receives the message
 *      TracingConsumer (from InfrastructureConfig) extracts header
 *      → child span: traceId=abc123, spanId=003 (SAME trace, new span)
 *
 *   → All spans flushed to Zipkin by auto-configured AsyncReporter
 *   → Zipkin UI: search "search-service" → see full chain ✅
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ZIPKIN UI — what you will see after the fix:
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   Service dropdown: search-service ← this was missing before
 *
 *   Trace tree for one search request:
 *   ├─ [001] GET /search/products        (root, ~3ms)
 *   │  ├─ [002] es.search                (child, ~2ms — use TracingHelper)
 *   │  └─ [003] redis.get fallback       (child, ~0.5ms — use TracingHelper)
 *
 *   Trace tree for one Kafka-triggered index update:
 *   ├─ [001] kafka.receive catalog.product-events  (root from producer service)
 *   │  └─ [002] onCatalogEvent product.created     (child, ~100ms)
 *   │     └─ [003] es.index                        (child, ~80ms)
 */
@Configuration
public class TracingConfig {

    /**
     * KafkaTracing — Brave instrumentation for Kafka producers and consumers.
     *
     * The Tracing bean is auto-configured by Spring Boot via
     * BraveAutoConfiguration (triggered by micrometer-tracing-bridge-brave
     * on the classpath). We inject it here — no manual Tracing bean needed.
     *
     * This bean is consumed by InfrastructureConfig:
     *   producerFactory(KafkaTracing kafkaTracing) — wraps the raw Producer
     *   consumerFactory(KafkaTracing kafkaTracing) — wraps the raw Consumer
     *
     * kafkaTracing.producer(rawProducer)
     *   → Returns TracingProducer
     *   → Before each send(): injects "traceparent: 00-<traceId>-<spanId>-01"
     *
     * kafkaTracing.consumer(rawConsumer)
     *   → Returns TracingConsumer
     *   → Before each poll(): extracts "traceparent" header, restores context
     *   → @KafkaListener method runs under the SAME traceId as the producer
     */
    @Bean
    KafkaTracing kafkaTracing(Tracing tracing) {
        return KafkaTracing.newBuilder(tracing)
                .remoteServiceName("kafka")
                .build();
    }
}