package io.tradeflow.fraud.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.ttddyy.observation.boot.autoconfigure.DataSourceObservationAutoConfiguration;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * FraudConfig — main configuration for Fraud Detection Service.
 *
 * TRACING ADDITIONS vs original:
 *
 * 1. @ImportAutoConfiguration(DataSourceObservationAutoConfiguration.class)
 *    → enables JDBC tracing: fraud_scores_audit INSERT + Spring Batch SQL
 *      appear as child spans of the parent scoring trace.
 *
 * 2. ObservedAspect bean
 *    → enables @Observed annotation on FraudScoringService methods
 *      (score(), processFeedback(), runMlScoring(), etc.).
 *
 * 3. KafkaTemplate.setObservationEnabled(true)
 *    → injects "traceparent" W3C header into every Kafka message produced.
 *    → fraud.events and fraud.alerts messages carry the traceId from the
 *      original cmd.score-transaction message that started the SAGA.
 *
 * 4. kafkaListenerContainerFactory.setObservationEnabled(true)
 *    → CRITICAL: extracts "traceparent" from incoming Kafka headers.
 *    → When Order Service sends cmd.score-transaction with traceId=abc123,
 *      Fraud Service continues the SAME trace (still abc123).
 *    → Without this: every cmd.score-transaction creates a new root span.
 *      You lose the ability to trace an order from placement → fraud scoring
 *      → payment → delivery in one Zipkin trace.
 *
 * KAFKA DESERIALIZER NOTE (unchanged from original):
 * cmd.score-transaction is published by Order Service WITHOUT Spring type
 * headers (ADD_TYPE_INFO_HEADERS=false). ErrorHandlingDeserializer +
 * VALUE_DEFAULT_TYPE=HashMap handles this correctly.
 * Observation extraction happens at the container level, before
 * deserialization, so there is no conflict.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT55S")
@EnableKafka
@ImportAutoConfiguration(DataSourceObservationAutoConfiguration.class) // ✅ JDBC tracing
public class FraudConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwksUri;

    @Value("${spring.kafka.bootstrap-servers}")
    private String kafkaBootstrap;

    // ─────────────────────────────────────────────────────────────────────────
    // TRACING — ObservedAspect enables @Observed on any Spring bean method
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Enables @Observed(name = "fraud.score") on FraudScoringService.score().
     * Each annotated method creates a named child span visible in Zipkin.
     */
    @Bean
    ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Security — unchanged
    // ─────────────────────────────────────────────────────────────────────────

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(jwtDecoder()).jwtAuthenticationConverter(jwtConverter())));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
    }

    @Bean
    JwtAuthenticationConverter jwtConverter() {
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null) return List.of();
            return roles.stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .collect(Collectors.toList());
        });
        return c;
    }

    @Bean
    TomcatProtocolHandlerCustomizer<?> virtualThreadsExecutor() {
        return p -> p.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRODUCER — publishes to fraud.events + fraud.alerts
    //
    // ✅ TRACING: setObservationEnabled(true) on KafkaTemplate injects the
    // current traceId into message headers (W3C "traceparent").
    //
    // When Order Service consumes fraud.events (fraud.score.computed), it
    // reads that header and continues the SAME trace that started when the
    // buyer placed the order. One traceId from order placement → fraud score
    // → payment → delivery.
    //
    // ADD_TYPE_INFO_HEADERS=false kept — plain JSON, no Spring type headers.
    // ─────────────────────────────────────────────────────────────────────────

    @Bean
    ProducerFactory<String, Map<String, Object>> producerFactory() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,      kafkaBootstrap);
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        cfg.put(ProducerConfig.ACKS_CONFIG,                   "all");
        cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,     true);
        cfg.put(JsonSerializer.ADD_TYPE_INFO_HEADERS,         false); // plain JSON
        return new DefaultKafkaProducerFactory<>(cfg);
    }

    @Bean
    KafkaTemplate<String, Map<String, Object>> kafkaTemplate(
            ObservationRegistry observationRegistry) {
        KafkaTemplate<String, Map<String, Object>> template =
                new KafkaTemplate<>(producerFactory());

        // ✅ CRITICAL — injects traceId into every Kafka message header on send()
        template.setObservationEnabled(true);

        return template;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONSUMER — accepts plain JSON without Spring type headers
    //
    // ✅ TRACING: kafkaListenerContainerFactory.setObservationEnabled(true)
    // extracts the W3C "traceparent" header from incoming messages.
    //
    // This is the most important single line for cross-service trace continuity:
    //   Order Service sends cmd.score-transaction with traceId=XYZ
    //   → Fraud Service extracts XYZ from headers
    //   → All downstream spans (MongoDB vector search, PG audit INSERT,
    //     Redis OFAC check, Spring AI embedding call) inherit traceId=XYZ
    //   → Fraud Service publishes fraud.events with traceId=XYZ still in headers
    //   → Order Service consumer picks up XYZ and continues the same trace
    //
    // DESERIALIZER NOTE (unchanged from original CRITICAL FIX):
    //   ErrorHandlingDeserializer + USE_TYPE_INFO_HEADERS=false + VALUE_DEFAULT_TYPE=HashMap
    //   These are needed because cmd.score-transaction has no __TypeId__ header.
    //   Observation extraction happens at container level before deserialization.
    // ─────────────────────────────────────────────────────────────────────────

    @Bean
    ConsumerFactory<String, Map<String, Object>> consumerFactory() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,  kafkaBootstrap);
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,  "earliest");
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // Key deserializer
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                ErrorHandlingDeserializer.class);
        cfg.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS,
                StringDeserializer.class);

        // Value deserializer
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ErrorHandlingDeserializer.class);
        cfg.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS,
                JsonDeserializer.class);

        // CRITICAL FIX (unchanged): accept plain JSON without __TypeId__ header
        cfg.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        cfg.put(JsonDeserializer.VALUE_DEFAULT_TYPE,    "java.util.HashMap");
        cfg.put(JsonDeserializer.TRUSTED_PACKAGES,      "*");

        return new DefaultKafkaConsumerFactory<>(cfg);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, Map<String, Object>> kafkaListenerContainerFactory(
            ObservationRegistry observationRegistry) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Map<String, Object>>();
        factory.setConsumerFactory(consumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setConcurrency(3);

        // ✅ CRITICAL — extracts traceId from Kafka headers → continues the upstream trace
        factory.getContainerProperties().setObservationEnabled(true);

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
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}