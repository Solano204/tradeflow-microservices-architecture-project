package io.tradeflow.identity.config;

import brave.kafka.clients.KafkaTracing;
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
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.web.client.RestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * InfrastructureConfig — Identity Service infrastructure beans.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * KEY FIXES APPLIED IN THIS VERSION:
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * FIX 1 — Removed TracingProducerInterceptor / TracingConsumerInterceptor.
 *   These are string class names registered via ProducerConfig.INTERCEPTOR_CLASSES_CONFIG.
 *   They use B3 headers by default, conflicting with the W3C propagation format
 *   set in application.properties (management.tracing.propagation.type=W3C).
 *   The result was: Auth Service (Quarkus OTel, W3C) could not read Identity's
 *   B3 headers → no cross-service trace linking → Auth never appeared in Zipkin.
 *
 *   REPLACED WITH: kafkaTracing.producer() / kafkaTracing.consumer() wrapper pattern.
 *   KafkaTracing is configured by Spring Boot's BraveAutoConfiguration to use
 *   the SAME propagation format as the rest of the app (W3C in our case).
 *
 * FIX 2 — KafkaTracing bean uses writeB3SingleFormat(false) to match W3C.
 *   The old code had writeB3SingleFormat(true) which forced B3 format even when
 *   the rest of the tracing stack was configured for W3C. Now both are consistent.
 *
 * FIX 3 — RestTemplate now uses RestTemplateBuilder (injected by Spring).
 *   Spring Boot auto-applies ObservationRestTemplateCustomizer to the builder,
 *   which adds Micrometer tracing to all outbound HTTP calls (Jumio API).
 *   The old `new RestTemplate()` produced zero HTTP client spans.
 *
 * FIX 4 — TracingConfig.java (Redis AOP aspect) should be DELETED.
 *   Lettuce + micrometer-tracing-bridge-brave auto-instruments Redis via
 *   the Micrometer Observation API. The AOP aspect creates duplicate spans
 *   and can interfere with span context propagation.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * TRACE PROPAGATION FORMAT — MUST BE CONSISTENT ACROSS ALL SERVICES:
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   Identity Service (Spring Boot 3, Brave):
 *     management.tracing.propagation.type=W3C   ← application.properties
 *     KafkaTracing.writeB3SingleFormat = false   ← this file
 *     Result: injects "traceparent" header (W3C format)
 *
 *   Auth Service (Quarkus, OTel):
 *     quarkus.otel.propagators=tracecontext      ← application.properties
 *     Result: reads "traceparent" header (W3C format)
 *
 *   Search Service (Spring Boot 3, Brave):
 *     management.tracing.propagation.type=W3C   ← application.properties
 *     KafkaTracing.writeB3SingleFormat = false   ← TracingConfig.java
 *     Result: injects/reads "traceparent" header (W3C format)
 *
 *   ALL THREE MATCH → cross-service traces are linked correctly in Zipkin.
 */
@Configuration
@EnableKafka
public class InfrastructureConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String kafkaBootstrapServers;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    // ═══════════════════════════════════════════════════════════════════════
    // VIRTUAL THREADS
    // ═══════════════════════════════════════════════════════════════════════

    @Bean
    public TomcatProtocolHandlerCustomizer<?> virtualThreadsExecutor() {
        return protocolHandler ->
                protocolHandler.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // KAFKA TRACING BEAN
    //
    // Spring Boot BraveAutoConfiguration creates the Brave Tracing bean
    // configured for W3C propagation (from management.tracing.propagation.type=W3C).
    // We build KafkaTracing on top of it.
    //
    // writeB3SingleFormat(false) = do NOT force B3 headers.
    // Instead, KafkaTracing will use whatever propagation the Tracing bean
    // is configured for — which is W3C because of our properties file.
    //
    // This is the critical fix: old code had writeB3SingleFormat(true) which
    // forced B3 regardless of the propagation.type property. Auth Service
    // (Quarkus OTel) only reads W3C "traceparent" headers, so it could never
    // link its spans to Identity's traces.
    // ═══════════════════════════════════════════════════════════════════════

    @Bean
    public KafkaTracing kafkaTracing(brave.Tracing tracing) {
        return KafkaTracing.newBuilder(tracing)
                .remoteServiceName("kafka")
                .writeB3SingleFormat(false)  // ← FIXED: was true, forced B3 regardless of W3C config
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // KAFKA PRODUCER — wrapper pattern (NOT interceptor class strings)
    //
    // The wrapper pattern (kafkaTracing.producer()) is superior to
    // INTERCEPTOR_CLASSES_CONFIG because:
    //   1. It participates in the active OTel/Brave context directly
    //   2. It respects the propagation format configured in the Tracing bean
    //   3. It creates proper parent-child span relationships
    //   4. Interceptor class strings are instantiated by Kafka reflection,
    //      outside Spring's context — they cannot access the Tracing bean
    //      and fall back to default B3 format regardless of your config
    // ═══════════════════════════════════════════════════════════════════════

    @Bean
    public ProducerFactory<String, Map<String, Object>> producerFactory(KafkaTracing kafkaTracing) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.RETRIES_CONFIG, 3);
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // NO INTERCEPTOR_CLASSES_CONFIG — see class javadoc for why

        return new DefaultKafkaProducerFactory<>(config) {
            @Override
            protected org.apache.kafka.clients.producer.Producer<String, Map<String, Object>> createKafkaProducer() {
                // kafkaTracing.producer() wraps with W3C traceparent injection
                return kafkaTracing.producer(super.createKafkaProducer());
            }
        };
    }

    @Bean
    public KafkaTemplate<String, Map<String, Object>> kafkaTemplate(
            ProducerFactory<String, Map<String, Object>> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // KAFKA CONSUMER — wrapper pattern (NOT interceptor class strings)
    //
    // Same reasoning as producer above.
    // ErrorHandlingDeserializer wraps JsonDeserializer to prevent
    // deserialization failures from crashing the consumer thread.
    // ═══════════════════════════════════════════════════════════════════════

    @Bean
    public ConsumerFactory<String, Map<String, Object>> consumerFactory(KafkaTracing kafkaTracing) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class.getName());
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "java.util,java.lang");
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "java.util.HashMap");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // NO INTERCEPTOR_CLASSES_CONFIG — see class javadoc for why

        return new DefaultKafkaConsumerFactory<>(config) {
            @Override
            protected org.apache.kafka.clients.consumer.Consumer<String, Map<String, Object>> createKafkaConsumer(
                    String groupId, String clientIdSuffix, String clientId,
                    java.util.Properties properties) {
                // kafkaTracing.consumer() wraps with W3C traceparent extraction
                return kafkaTracing.consumer(
                        super.createKafkaConsumer(groupId, clientIdSuffix, clientId, properties));
            }
        };
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Map<String, Object>> kafkaListenerContainerFactory(
            ConsumerFactory<String, Map<String, Object>> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Map<String, Object>> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setConcurrency(3);
        // Retry 3x with 1s delay — prevents poison pill messages from blocking forever
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, 3L)));
        return factory;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // REDIS
    // ═══════════════════════════════════════════════════════════════════════

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SHEDLOCK
    // ═══════════════════════════════════════════════════════════════════════

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build()
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AWS S3
    // ═══════════════════════════════════════════════════════════════════════

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HTTP CLIENT — RestTemplate with Micrometer tracing
    //
    // CRITICAL FIX: use RestTemplateBuilder (injected by Spring), NOT
    // new RestTemplate(). Spring Boot auto-applies
    // ObservationRestTemplateCustomizer to the builder, which adds
    // Micrometer tracing interceptors to all HTTP calls.
    //
    // OLD (broken): return new RestTemplate();
    //   → No outbound HTTP spans
    //   → Jumio calls invisible in Zipkin
    //   → No traceparent header injected into Jumio requests
    //
    // NEW (correct): return builder.build();
    //   → Every restTemplate.exchange() creates a child span
    //   → traceparent header injected automatically
    //   → Jumio call visible as child span under the KYC upload trace
    // ═══════════════════════════════════════════════════════════════════════

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // JACKSON
    // ═══════════════════════════════════════════════════════════════════════

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}