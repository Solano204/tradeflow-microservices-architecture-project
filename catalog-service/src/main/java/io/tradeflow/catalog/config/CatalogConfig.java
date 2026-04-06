package io.tradeflow.catalog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.observation.ObservationRegistry;
import io.tradeflow.catalog.dto.CatalogDtos.ErrorResponse;
import io.tradeflow.catalog.service.*;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

// ─────────────────────────────────────────────────────────────────────────────
// SECURITY CONFIG
// ─────────────────────────────────────────────────────────────────────────────

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwksUri;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health/**", "/health/ready",
                                "/swagger-ui/**", "/v3/api-docs/**",
                                "/categories", "/categories/*/products",  // public browse
                                "/products/*"                              // public product detail
                        ).permitAll()
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(jwtDecoder()).jwtAuthenticationConverter(jwtAuthConverter())));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthConverter() {
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
}

// ─────────────────────────────────────────────────────────────────────────────
// INFRASTRUCTURE CONFIG
// ─────────────────────────────────────────────────────────────────────────────

@Configuration
class InfrastructureConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String kafkaBootstrapServers;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Bean
    TomcatProtocolHandlerCustomizer<?> virtualThreadsExecutor() {
        return p -> p.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    @Bean
    ProducerFactory<String, Map<String, Object>> producerFactory() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        cfg.put(ProducerConfig.ACKS_CONFIG, "all");
        cfg.put(ProducerConfig.RETRIES_CONFIG, 3);
        cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(cfg);
    }

    /**
     * ✅ TRACING — KafkaTemplate with observation enabled.
     *
     * When you call kafkaTemplate.send(...), Micrometer automatically:
     * 1. Creates a child span for the Kafka PRODUCE operation
     * 2. Injects the current traceId into the Kafka message headers (W3C "traceparent")
     *
     * The consumer on the other end reads that header and continues the same trace.
     * This is how a single traceId flows across Kafka topic boundaries.
     */
    @Bean
    KafkaTemplate<String, Map<String, Object>> kafkaTemplate(
            ObservationRegistry observationRegistry) {
        KafkaTemplate<String, Map<String, Object>> template =
                new KafkaTemplate<>(producerFactory());

        // ✅ THIS LINE injects traceId into Kafka message headers on every send()
        template.setObservationEnabled(true);

        return template;
    }

    @Bean
    ConsumerFactory<String, Map<String, Object>> consumerFactory() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        cfg.put(JsonDeserializer.TRUSTED_PACKAGES, "java.util,java.lang");
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(cfg);
    }

    // NOTE: kafkaListenerContainerFactory is now in TracingConfig.java
    // It's defined there so observation can be enabled in one place.
    // Remove it from here if you had it before.

    @Bean
    StringRedisTemplate stringRedisTemplate(RedisConnectionFactory cf) {
        return new StringRedisTemplate(cf);
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
    S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .build();
    }

    @Bean
    ObjectMapper objectMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return m;
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// GLOBAL EXCEPTION HANDLER
// ─────────────────────────────────────────────────────────────────────────────

@RestControllerAdvice
@Slf4j
class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(NotFoundException e) {
        return ResponseEntity.status(404).body(ErrorResponse.of("NOT_FOUND", e.getMessage(), 404));
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ErrorResponse> conflict(ConflictException e) {
        return ResponseEntity.status(409).body(ErrorResponse.of("CONFLICT", e.getMessage(), 409));
    }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ErrorResponse> badRequest(BadRequestException e) {
        return ResponseEntity.status(400).body(ErrorResponse.of("BAD_REQUEST", e.getMessage(), 400));
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ErrorResponse> forbidden(ForbiddenException e) {
        return ResponseEntity.status(403).body(ErrorResponse.of("FORBIDDEN", e.getMessage(), 403));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> accessDenied(AccessDeniedException e) {
        return ResponseEntity.status(403).body(ErrorResponse.of("FORBIDDEN", "Access denied", 403));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage).collect(Collectors.joining(", "));
        return ResponseEntity.status(400).body(ErrorResponse.of("VALIDATION_ERROR", msg, 400));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> generic(Exception e) {
        log.error("Unhandled: {}", e.getMessage(), e);
        return ResponseEntity.status(500)
                .body(ErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred", 500));
    }
}
