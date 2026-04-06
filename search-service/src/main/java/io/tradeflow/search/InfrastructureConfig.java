package io.tradeflow.search;

import brave.kafka.clients.KafkaTracing;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.HttpHost;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * InfrastructureConfig — single source of truth for all infrastructure beans.
 *
 * DELETE these files — they duplicate beans defined here and cause
 * BeanDefinitionOverrideException at startup:
 *   ❌ IdentityKafkaConfig.java   (duplicate producerFactory, kafkaTemplate,
 *                                  consumerFactory, kafkaListenerContainerFactory)
 *   ❌ WebClientTracingConfig.java (duplicate webClientBuilder)
 *   ❌ TracingConfig.java          (manually re-creates beans Spring Boot
 *                                  auto-configures from application.properties:
 *                                  URLConnectionSender, AsyncReporter,
 *                                  ZipkinSpanHandler — all redundant in SB3)
 *
 * WHAT SPRING BOOT 3.x AUTO-CONFIGURES (no manual beans needed):
 *   ✅ Brave Tracing — from micrometer-tracing-bridge-brave on classpath
 *   ✅ Zipkin reporter — from management.zipkin.tracing.endpoint property
 *   ✅ HTTP tracing — Spring MVC + WebClient observe every request/call
 *   ✅ KafkaTracing bean — from brave-instrumentation-kafka-clients on classpath
 *      HOWEVER: Spring Boot does NOT auto-wrap your Producer/Consumer with it.
 *      You must do that manually in the factory overrides below.
 *
 * TRACE FLOW:
 *   GET /search/products arrives
 *   → Micrometer auto-creates root span (traceId=abc123, spanId=001)
 *   → ES query runs — RestClient NOT auto-traced (no Brave ES instrumentation)
 *      Use TracingHelper.observe("es.search", ...) for manual ES spans
 *   → If kafka event consumed:
 *      TracingConsumer extracts "traceparent" header → child span (traceId=abc123)
 *   → Span sent to Zipkin via AsyncReporter (auto-configured by Spring Boot)
 *   → Zipkin shows search-service in the service dropdown ✅
 */
@Configuration
@EnableKafka
public class InfrastructureConfig {

    @Value("${elasticsearch.host:localhost}")
    private String esHost;

    @Value("${elasticsearch.port:9200}")
    private int esPort;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String kafkaBootstrap;

    // ═══════════════════════════════════════════════════════════════════════
    // ELASTICSEARCH
    // ═══════════════════════════════════════════════════════════════════════

    @Bean
    ElasticsearchClient elasticsearchClient(ObjectMapper objectMapper) {
        RestClient restClient = RestClient.builder(
                new HttpHost(esHost, esPort, "http")).build();
        ElasticsearchTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper(objectMapper));
        return new ElasticsearchClient(transport);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // VIRTUAL THREADS
    // ═══════════════════════════════════════════════════════════════════════

    @Bean
    TomcatProtocolHandlerCustomizer<?> virtualThreadsExecutor() {
        return p -> p.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WEB CLIENT
    //
    // Spring Boot auto-applies ObservationWebClientCustomizer to any
    // WebClient.Builder bean, which injects the traceparent header into
    // every outbound HTTP call automatically.
    //
    // InternalServiceClients injects WebClient.Builder — it gets tracing
    // for free. Do NOT use WebClient.create() directly (no tracing).
    // ═══════════════════════════════════════════════════════════════════════

    @Bean
    WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // KAFKA PRODUCER — with Brave tracing
    //
    // KafkaTracing is auto-configured by Spring Boot when
    // brave-instrumentation-kafka-clients is on the classpath.
    // We inject it here to wrap the raw Producer so it injects
    // the "traceparent" header on every send().
    // ═══════════════════════════════════════════════════════════════════════

    @Bean
    ProducerFactory<String, Object> producerFactory(KafkaTracing kafkaTracing) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.RETRIES_CONFIG, 3);
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // Do NOT add __TypeId__ headers — downstream services don't expect them
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        return new DefaultKafkaProducerFactory<>(config) {
            @Override
            protected org.apache.kafka.clients.producer.Producer<String, Object> createKafkaProducer() {
                // TracingProducer injects "traceparent" header on every send()
                // so the receiving service can continue the same trace
                return kafkaTracing.producer(super.createKafkaProducer());
            }
        };
    }

    @Bean
    KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // KAFKA CONSUMER — with Brave tracing + deserialization error handling
    //
    // Two-layer deserialization protection:
    //   Layer 1 — ErrorHandlingDeserializer: catches parse failures,
    //             logs them, returns null instead of crashing the thread
    //   Layer 2 — DefaultErrorHandler below: retries 3×, then skips
    //
    // WHY USE_TYPE_INFO_HEADERS=false:
    //   Catalog and Inventory services don't publish __TypeId__ headers.
    //   Without this flag, JsonDeserializer throws "No type information"
    //   on EVERY message and the consumer blocks at offset 0 forever.
    // ═══════════════════════════════════════════════════════════════════════

    @Bean
    ConsumerFactory<String, Object> consumerFactory(KafkaTracing kafkaTracing) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap);
        // NOTE: group ID is set per-listener via @KafkaListener(groupId=...)
        // Setting it here would force ALL listeners to the same group,
        // which would break search-indexer vs search-merchant-event-processor
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);

        // Outer deserializer: catches failures, never crashes the consumer thread
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);

        // Inner deserializer: actual JSON → HashMap conversion
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class.getName());

        // CRITICAL: catalog.product-events and inventory.events don't have __TypeId__
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "java.util.HashMap");
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "*");

        return new DefaultKafkaConsumerFactory<>(config) {
            @Override
            protected org.apache.kafka.clients.consumer.Consumer<String, Object> createKafkaConsumer(
                    String groupId, String clientIdSuffix, String clientId,
                    java.util.Properties properties) {
                // TracingConsumer extracts "traceparent" header from each message
                // and links the @KafkaListener processing to the originating traceId.
                // This is what makes cross-service Kafka traces visible in Zipkin.
                return kafkaTracing.consumer(
                        super.createKafkaConsumer(groupId, clientIdSuffix, clientId, properties));
            }
        };
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        // 2 threads: one for catalog.product-events, one for inventory.events
        factory.setConcurrency(2);

        // Retry 3× with 1s delay, then skip the message.
        // Prevents a single bad message from blocking the partition forever.
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, 3L)));

        return factory;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // JACKSON
    // ═══════════════════════════════════════════════════════════════════════

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}