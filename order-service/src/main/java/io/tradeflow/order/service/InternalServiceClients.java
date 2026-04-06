package io.tradeflow.order.service;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Internal service clients for pre-validation in POST /orders.
 *
 * All calls are wrapped with the full Resilience4j stack per spec:
 *   Bulkhead → CircuitBreaker → Retry → TimeLimiter
 *
 * These calls are SYNCHRONOUS reads before the SAGA starts —
 * they eliminate the obvious failure cases cheaply rather than
 * starting a full SAGA only to compensate immediately.
 *
 * Note: these are hints, not guarantees (stock can sell out
 * between check and reserve — Inventory's @Version OL handles that).
 */
@Component
@Slf4j
public class InternalServiceClients {

    private final WebClient inventoryClient;
    private final WebClient identityClient;
    private final WebClient catalogClient;

    public InternalServiceClients(
            @Value("${services.inventory.url:http://inventory-service:8083}") String inventoryUrl,
            @Value("${services.identity.url:http://identity-service:8081}") String identityUrl,
            @Value("${services.catalog.url:http://catalog-service:8082}") String catalogUrl) {
        this.inventoryClient = WebClient.builder().baseUrl(inventoryUrl).build();
        this.identityClient  = WebClient.builder().baseUrl(identityUrl).build();
        this.catalogClient   = WebClient.builder().baseUrl(catalogUrl).build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inventory availability check (Endpoint 8 of Inventory Service)
    // ─────────────────────────────────────────────────────────────────────────

    @Bulkhead(name = "inventory-check")
    @CircuitBreaker(name = "inventory-check", fallbackMethod = "inventoryCheckFallback")
    @Retry(name = "internal-calls")
    @TimeLimiter(name = "internal-calls")
    public CompletableFuture<Boolean> checkInventoryAvailable(String productId, int qty) {
        return inventoryClient.get()
                .uri("/internal/inventory/{productId}/available?qty={qty}", productId, qty)
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> Boolean.TRUE.equals(body.get("isAvailable")))

                .toFuture();
    }

    @SuppressWarnings("unused")
    CompletableFuture<Boolean> inventoryCheckFallback(String productId, int qty, Throwable t) {
        // Circuit open or timeout — assume available, let SAGA's reserve handle it
        log.warn("Inventory check circuit open for productId={}, assuming available: {}", productId, t.getMessage());
        return CompletableFuture.completedFuture(true);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Merchant status check (Identity Service internal endpoint)
    // ─────────────────────────────────────────────────────────────────────────

    @Bulkhead(name = "identity-check")
    @CircuitBreaker(name = "identity-check", fallbackMethod = "merchantCheckFallback")
    @Retry(name = "internal-calls")
    @TimeLimiter(name = "internal-calls")
    public CompletableFuture<String> getMerchantStatus(String merchantId) {
        return identityClient.get()
                .uri("/internal/merchants/{merchantId}/status", merchantId)
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> (String) body.getOrDefault("status", "ACTIVE"))
                .toFuture();
    }

    @SuppressWarnings("unused")
    CompletableFuture<String> merchantCheckFallback(String merchantId, Throwable t) {
        // Circuit open — assume ACTIVE, downstream fraud scoring will catch issues
        log.warn("Identity check circuit open for merchantId={}, assuming ACTIVE: {}", merchantId, t.getMessage());
        return CompletableFuture.completedFuture("ACTIVE");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Product price check (Catalog Service — price integrity validation)
    // ─────────────────────────────────────────────────────────────────────────

    @Bulkhead(name = "catalog-check")
    @CircuitBreaker(name = "catalog-check", fallbackMethod = "priceCheckFallback")
    @Retry(name = "internal-calls")
    @TimeLimiter(name = "internal-calls")
    public CompletableFuture<BigDecimal> getCurrentPrice(String productId) {
        return catalogClient.get()
                .uri("/products/{productId}", productId)
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> {
                    Map<?, ?> price = (Map<?, ?>) body.get("price");
                    if (price == null) return null;
                    Object amount = price.get("amount");
                    return amount != null ? new BigDecimal(amount.toString()) : null;
                })
                .toFuture();
    }

    @SuppressWarnings("unused")
    CompletableFuture<BigDecimal> priceCheckFallback(String productId, Throwable t) {
        // Circuit open — skip price validation, let order proceed
        log.warn("Catalog check circuit open for productId={}, skipping price validation: {}", productId, t.getMessage());
        return CompletableFuture.completedFuture(null);
    }
}
