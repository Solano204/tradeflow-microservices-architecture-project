package io.tradeflow.search.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tradeflow.search.dto.SearchDtos.*;
import io.tradeflow.search.service.SearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequiredArgsConstructor
@Tag(name = "Search", description = "Elasticsearch-powered product search, autocomplete, and index management")
public class SearchController {

    private final SearchService searchService;

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 1 — GET /search/products
    //
    // Primary product discovery. Works for guests (no JWT required).
    // BM25 text relevance × function score boosts × facet aggregations.
    // Single ES request returns both ranked results AND the filter panel.
    // Circuit Breaker open → Redis fallback top-1000.
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/search/products")
    @Operation(summary = "Full-text product search with facets, filters, and relevance scoring",
               description = "BM25 + synonym expansion + function score (recency × sales × rating × geo). " +
                             "Single Elasticsearch request returns results AND facet panel. " +
                             "Circuit Breaker: falls back to Redis pre-cached top-1000 if ES unavailable.")
    public CompletableFuture<SearchResponse> searchProducts(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(required = false) String brand,    // comma-separated
            @RequestParam(required = false) String color,
            @RequestParam(required = false) Double rating,
            @RequestParam(defaultValue = "false") boolean available,
            @RequestParam(defaultValue = "relevance") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lon) {

        List<String> brands = brand != null && !brand.isBlank()
                ? Arrays.asList(brand.split(","))
                : null;

        SearchParams params = new SearchParams(
                q, category, minPrice, maxPrice, brands, color, rating,
                available, sort, page, Math.min(size, 100), lat, lon);

        return searchService.search(params);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 2 — GET /search/autocomplete
    //
    // Sub-50ms. Edge NGram pre-indexed tokens → O(1) term lookup.
    // No aggregations (saves 5-10ms). Minimal _source fetch.
    // Distinct from full search — different query shape, different optimization target.
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/search/autocomplete")
    @Operation(summary = "Prefix suggestions as buyer types — sub-50ms target",
               description = "Edge NGram tokenizer: 'Sams' pre-indexed as token → O(1) term lookup. " +
                             "No aggregations. Returns product, brand, and query suggestions. " +
                             "Sorted by sales_count — most popular completions first.")
    public CompletableFuture<AutocompleteResponse> autocomplete(
            @RequestParam String q,
            @RequestParam(defaultValue = "8") int size,
            @RequestParam(required = false) String category) {
        return searchService.autocomplete(q, Math.min(size, 20), category);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 3 — GET /search/products/{id}/similar
    //
    // Elasticsearch MLT — term significance, no explicit similarity rules.
    // The index's language captures similarity implicitly.
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/search/products/{productId}/similar")
    @Operation(summary = "More Like This — similar product recommendations",
               description = "Elasticsearch MLT query: analyzes title, description, attributes.brand. " +
                             "Finds top 20 most distinctive terms → queries for similar documents. " +
                             "No explicit rules: same brand/category emerges naturally from term analysis.")
    public CompletableFuture<SimilarProductsResponse> findSimilar(
            @PathVariable String productId,
            @RequestParam(defaultValue = "6") int size,
            @RequestParam(defaultValue = "false") boolean excludeSameMerchant,
            @RequestParam(required = false) Double maxPrice) {
        return searchService.findSimilar(productId, Math.min(size, 20), excludeSameMerchant, maxPrice);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 4 — GET /search/categories/{id}/products
    //
    // Category browse — no text query, pure filter + function score.
    // Adds subcategory breakdown facet. Default sort: best_selling.
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/search/categories/{categoryId}/products")
    @Operation(summary = "Browse category with search-quality ranking",
               description = "term filter on category_id (not BM25). Function score boosts applied. " +
                             "Adds subcategory facet. Default sort: best_selling (sales_count DESC).")
    public CompletableFuture<CategoryBrowseResponse> browseCategory(
            @PathVariable String categoryId,
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(required = false) String brand,
            @RequestParam(defaultValue = "false") boolean available,
            @RequestParam(defaultValue = "best_selling") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size) {

        List<String> brands = brand != null ? Arrays.asList(brand.split(",")) : null;
        SearchParams params = new SearchParams(
                null, null, minPrice, maxPrice, brands, null, null,
                available, sort, page, Math.min(size, 100), null, null);

        return searchService.browseCategory(categoryId, params);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 5 — POST /internal/search/index/products
    // Admin recovery — force re-index single product from fresh catalog data
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/internal/search/index/products")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Force re-index single product (admin recovery)",
               description = "Fetches fresh data from Product Catalog, bypasses cache. " +
                             "Used when index document is suspected stale after consumer lag.",
               security = @SecurityRequirement(name = "bearerAuth"))
    public IndexProductResponse reindexProduct(@Valid @RequestBody IndexProductRequest request) {
        return searchService.reindexProduct(request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 6 — DELETE /internal/search/index/products/{id}
    // Immediate removal — delist / merchant termination cascade
    // ─────────────────────────────────────────────────────────────────────────

    @DeleteMapping("/internal/search/index/products/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Remove product from index",
               description = "Immediate deletion — product vanishes from search results within ES refresh interval (1s). " +
                             "Called by Kafka consumer on product.delisted, also available as admin operation.",
               security = @SecurityRequirement(name = "bearerAuth"))
    public void deleteFromIndex(@PathVariable String productId) {
        searchService.deleteFromIndex(productId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 7 — POST /internal/search/index/rebuild
    // Blue-green zero-downtime full index rebuild
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/internal/search/index/rebuild")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Full index rebuild — zero-downtime blue-green",
               description = "Creates new versioned index, bulk indexes all products, " +
                             "atomically swaps alias. Application code never touches physical index name. " +
                             "Requires confirm='yes' to proceed.",
               security = @SecurityRequirement(name = "bearerAuth"))
    public RebuildResponse rebuildIndex(@Valid @RequestBody RebuildRequest request) {
        return searchService.rebuildIndex(request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 8 — GET /internal/search/index/health
    // Index health including Kafka consumer lag — the eventual consistency monitor
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/internal/search/index/health")
    @Operation(summary = "Index health — doc count, shard status, Kafka consumer lag",
               description = "Kafka consumer lag is the primary metric: " +
                             "lag=0 → index perfectly current. " +
                             "lag>10,000 → Prometheus alert — buyers seeing stale data.")
    public IndexHealthResponse getIndexHealth() {
        return searchService.getIndexHealth();
    }
}
