package io.tradeflow.search.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class SearchDtos {

    private SearchDtos() {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 1 — GET /search/products
    // ─────────────────────────────────────────────────────────────────────────

    public record SearchParams(
            String q,
            String category,
            Double minPrice,
            Double maxPrice,
            List<String> brand,
            String color,
            Double rating,
            boolean available,
            String sort,   // relevance | price_asc | price_desc | newest | best_selling
            int page,
            int size,
            Double lat,
            Double lon
    ) {
        public boolean hasQuery()       { return q != null && !q.isBlank(); }
        public boolean hasCategory()    { return category != null && !category.isBlank(); }
        public boolean hasPriceRange()  { return minPrice != null || maxPrice != null; }
        public boolean hasBrands()      { return brand != null && !brand.isEmpty(); }
        public boolean hasColor()       { return color != null && !color.isBlank(); }
        public boolean hasMinRating()   { return rating != null; }
        public boolean hasGeoLocation() { return lat != null && lon != null; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductSearchResult(
            String id,
            String title,
            String merchantName,
            double price,
            String currency,
            float rating,
            boolean available,
            String thumbnailUrl,
            List<String> categoryPath,
            Map<String, Object> attributes,
            double relevanceScore,
            Double distanceKm
    ) {}

    public record FacetValue(String value, long count) {}
    public record PriceFacetValue(String label, long count) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SearchFacets(
            List<FacetValue> brands,
            List<PriceFacetValue> priceRanges,
            List<FacetValue> ratings,
            List<FacetValue> colors,
            Map<String, Long> availability
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SearchResponse(
            String query,
            long totalResults,
            int page,
            int pageSize,
            long tookMs,
            List<ProductSearchResult> results,
            SearchFacets facets,
            List<String> synonymsApplied,
            String spellCorrection
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 2 — GET /search/autocomplete
    // ─────────────────────────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AutocompleteSuggestion(
            String text,
            String productId,
            String category,
            Double price,
            String thumbnail,
            String type,        // PRODUCT | BRAND | QUERY_SUGGESTION
            Long productCount
    ) {}

    public record AutocompleteResponse(
            String query,
            long tookMs,
            List<AutocompleteSuggestion> suggestions
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 3 — GET /search/products/{id}/similar
    // ─────────────────────────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SimilarProduct(
            String id,
            String title,
            double price,
            String similarityReason,
            float rating,
            boolean available,
            String thumbnailUrl
    ) {}

    public record SimilarProductsResponse(
            String referenceProductId,
            String referenceProductTitle,
            List<SimilarProduct> similarProducts
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 4 — GET /search/categories/{id}/products
    // ─────────────────────────────────────────────────────────────────────────

    public record CategoryBrowseResponse(
            String categoryId,
            long totalResults,
            int page,
            int pageSize,
            long tookMs,
            List<ProductSearchResult> results,
            SearchFacets facets,
            List<FacetValue> subcategories
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 5 — POST /internal/search/index/products
    // ─────────────────────────────────────────────────────────────────────────

    public record IndexProductRequest(
            String productId,
            String reason,
            String requestedBy
    ) {}

    public record IndexProductResponse(
            String productId,
            Instant indexedAt,
            String documentVersion,
            String indexedBy
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 7 — POST /internal/search/index/rebuild
    // ─────────────────────────────────────────────────────────────────────────

    public record RebuildRequest(
            String confirm,  // must equal "yes"
            String reason,
            String requestedBy
    ) {}

    public record RebuildResponse(
            String status,
            int docsIndexed,
            long timeSeconds,
            String newIndexName,
            Instant completedAt
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 8 — GET /internal/search/index/health
    // ─────────────────────────────────────────────────────────────────────────

    public record IndexDocumentStats(
            long total,
            long available,
            long outOfStock,
            long deletedPendingMerge
    ) {}

    public record IndexShardStats(
            int primary,
            int replicas,
            int unassigned
    ) {}

    public record IndexHealthResponse(
            String indexName,
            String alias,
            String health,
            String status,
            IndexDocumentStats documents,
            IndexShardStats shards,
            Map<String, Long> kafkaConsumerLag,
            Instant lastKafkaEventProcessed,
            double indexSizeGb,
            long queryLatencyP99Ms
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // SHARED
    // ─────────────────────────────────────────────────────────────────────────

    public record ErrorResponse(String error, String message, int status, Instant timestamp) {
        public static ErrorResponse of(String error, String message, int status) {
            return new ErrorResponse(error, message, status, Instant.now());
        }
    }
}
