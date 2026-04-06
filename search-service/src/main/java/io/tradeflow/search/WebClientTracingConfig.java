//package io.tradeflow.search;
//
//import io.micrometer.observation.ObservationRegistry;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.web.reactive.function.client.WebClient;
//
///**
// * WebClientTracingConfig — ensures outbound HTTP calls carry the traceId.
// *
// * When your Identity Service calls another service via WebClient,
// * the traceparent header MUST be injected automatically so the
// * downstream service continues the same trace.
// *
// * Spring Boot 3.x with Micrometer Tracing does this automatically
// * IF you use WebClient.Builder (injected by Spring) instead of
// * WebClient.create() directly.
// *
// * Spring Boot auto-configures ObservationWebClientCustomizer which
// * adds the tracing filter to every WebClient built via WebClient.Builder.
// *
// * USAGE IN YOUR SERVICES:
// *   ✅ CORRECT — use injected builder:
// *     @Autowired WebClient.Builder webClientBuilder;
// *     WebClient client = webClientBuilder.baseUrl("http://auth-service").build();
// *
// *   ❌ WRONG — don't create directly (tracing headers won't be injected):
// *     WebClient client = WebClient.create("http://auth-service");
// *
// * This bean exists to document the pattern. Spring Boot auto-configures
// * WebClient.Builder with observation support when micrometer-tracing
// * is on the classpath.
// */
//@Configuration
//public class WebClientTracingConfig {
//
//    /**
//     * WebClient.Builder is auto-configured by Spring Boot with the
//     * ObservationWebClientCustomizer already applied.
//     *
//     * This means any WebClient built from this builder will:
//     *   1. Create a child span for each outbound HTTP call
//     *   2. Inject traceparent header into the request automatically
//     *   3. Record the response status in the span
//     *
//     * You don't need to add anything to this builder — the tracing is
//     * applied automatically by Spring Boot's auto-configuration.
//     */
//    @Bean
//    public WebClient.Builder webClientBuilder() {
//        // Spring Boot auto-applies ObservationWebClientCustomizer to this builder
//        // The customizer adds tracing headers to every request automatically
//        return WebClient.builder();
//    }
//}