package io.tradeflow.search.client;

import io.tradeflow.search.document.ProductIndexDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Internal service clients — called when indexing products.
 *
 * product.created consumer:
 *   → GET /internal/products/{id}   from Product Catalog (rich document data)
 *   → GET /internal/inventory/{id}  from Inventory (availability status)
 *
 * rebuild job:
 *   → GET /internal/products?page=N&size=500 from Product Catalog (paginated)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InternalServiceClients {

    private final WebClient.Builder webClientBuilder;

    @Value("${services.catalog.url:http://catalog-service:8082}")
    private String catalogUrl;

    @Value("${services.inventory.url:http://inventory-service:8083}")
    private String inventoryUrl;

    /**
     * Fetch full product data for indexing.
     * Called by product.created consumer and manual re-index endpoint.
     */
    public ProductIndexDocument fetchProductForIndexing(String productId) {
        try {
            var client = webClientBuilder.baseUrl(catalogUrl).build();
            var catalogData = client.get()
                    .uri("/internal/products/{id}", productId)
                    .retrieve()
                    .bodyToMono(java.util.Map.class)
                    .block(java.time.Duration.ofSeconds(5));

            boolean available = fetchAvailability(productId);
            return buildDocumentFromCatalog(productId, catalogData, available);

        } catch (Exception e) {
            log.error("Failed to fetch product for indexing: productId={}: {}", productId, e.getMessage());
            // Return minimal stub — better to index partial data than fail completely
            return ProductIndexDocument.builder()
                    .id(productId)
                    .title("Product " + productId)
                    .available(false)
                    .build();
        }
    }

    /** Fetch a paginated batch of all active products for index rebuild. */
    @SuppressWarnings("unchecked")
    public List<ProductIndexDocument> fetchProductBatch(int page, int size) {
        try {
            var client = webClientBuilder.baseUrl(catalogUrl).build();
            var response = client.get()
                    .uri(u -> u.path("/internal/products")
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .queryParam("status", "ACTIVE")
                            .build())
                    .retrieve()
                    .bodyToMono(java.util.Map.class)
                    .block(java.time.Duration.ofSeconds(30));

            if (response == null) return List.of();

            List<java.util.Map<String, Object>> items = (List<java.util.Map<String, Object>>) response.get("products");
            if (items == null) return List.of();

            return items.stream()
                    .map(item -> {
                        String pid = (String) item.get("id");
                        boolean avail = fetchAvailability(pid);
                        return buildDocumentFromCatalog(pid, item, avail);
                    })
                    .toList();

        } catch (Exception e) {
            log.error("Failed to fetch product batch: page={}: {}", page, e.getMessage());
            return List.of();
        }
    }

    private boolean fetchAvailability(String productId) {
        try {
            var client = webClientBuilder.baseUrl(inventoryUrl).build();
            var resp = client.get()
                    .uri("/internal/inventory/{id}/available", productId)
                    .retrieve()
                    .bodyToMono(java.util.Map.class)
                    .block(java.time.Duration.ofSeconds(3));
            return resp != null && Boolean.TRUE.equals(resp.get("is_available"));
        } catch (Exception e) {
            log.debug("Availability check failed for {}: {}", productId, e.getMessage());
            return true; // default to available on failure
        }
    }

    @SuppressWarnings("unchecked")
    private ProductIndexDocument buildDocumentFromCatalog(String productId,
                                                          Map<String, Object> data,
                                                          boolean available) {
        if (data == null) return ProductIndexDocument.builder().id(productId).available(available).build();

        List<String> categoryPath = (List<String>) data.get("category_path");
        Map<String, Object> attributes = (Map<String, Object>) data.get("attributes");

        Object latObj = data.get("merchant_lat");
        Object lonObj = data.get("merchant_lon");
        ProductIndexDocument.GeoPoint location = (latObj != null && lonObj != null)
                ? new ProductIndexDocument.GeoPoint(
                ((Number) latObj).doubleValue(), ((Number) lonObj).doubleValue())
                : null;

        Object priceObj = data.get("price");
        double price = priceObj instanceof Number n ? n.doubleValue() : 0.0;

        Object ratingObj = data.get("rating");
        float rating = ratingObj instanceof Number n ? n.floatValue() : 0.0f;

        return ProductIndexDocument.builder()
                .id(productId)
                .title((String) data.getOrDefault("title", ""))
                .description((String) data.getOrDefault("description", ""))
                .merchantName((String) data.getOrDefault("merchant_name", ""))
                .merchantId((String) data.getOrDefault("merchant_id", ""))
                .categoryPath(categoryPath)
                .categoryId((String) data.getOrDefault("category_id", ""))
                .price(price)
                .currency((String) data.getOrDefault("currency", "MXN"))
                .rating(rating)
                .available(available)
                .thumbnailUrl((String) data.get("thumbnail_url"))
                .attributes(attributes)
                .location(location)
                .createdAt(java.time.Instant.now())
                .listedDaysAgo(0)
                .build();
    }
}
