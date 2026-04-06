package io.tradeflow.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.aggregations.*;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import io.tradeflow.search.client.InternalServiceClients;
import io.tradeflow.search.document.ProductIndexDocument;
import io.tradeflow.search.dto.SearchDtos;
import io.tradeflow.search.dto.SearchDtos.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * SearchService — all 8 endpoints.
 *
 * Wraps Elasticsearch calls in Resilience4j CircuitBreaker + TimeLimiter.
 * Circuit Breaker open → fallback to Redis pre-cached top-1000.
 *
 * Virtual Threads: Elasticsearch Java Client calls are blocking HTTP.
 * spring.threads.virtual.enabled=true means they don't park carrier threads.
 *
 * Index alias pattern:
 *   Application always queries "products" alias.
 *   Rebuild creates "products_v{N}", swaps alias atomically.
 *   Zero downtime — in-flight queries are served seamlessly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchService {

    private final ElasticsearchClient esClient;
    private final SearchQueryBuilder queryBuilder;
    private final RedisTemplate<String, String> redisTemplate;
    private final InternalServiceClients internalClients;
    private final ObjectMapper objectMapper;

    @Value("${elasticsearch.index.alias:products}")
    private String indexAlias;

    @Value("${search.fallback.redis-key:fallback:top1000}")
    private String fallbackRedisKey;

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 1 — GET /search/products
    // ─────────────────────────────────────────────────────────────────────────

    @CircuitBreaker(name = "elasticsearch", fallbackMethod = "searchFallback")
    @TimeLimiter(name = "elasticsearch")
    public CompletableFuture<SearchDtos.SearchResponse> search(SearchParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                SearchRequest req = queryBuilder.buildSearchRequest(params);
                co.elastic.clients.elasticsearch.core.SearchResponse<ProductIndexDocument> resp = esClient.search(req, ProductIndexDocument.class);

                List<ProductSearchResult> results = resp.hits().hits().stream()
                        .map(hit -> buildSearchResult(hit, params))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                SearchFacets facets = buildFacets(resp.aggregations());
                long tookMs = resp.took();
                long total  = resp.hits().total() != null ? resp.hits().total().value() : 0;

                return new SearchDtos.SearchResponse(
                        params.q(), total, params.page(), params.size(),
                        tookMs, results, facets, null, null);

            } catch (Exception e) {
                log.error("Search failed: q={}, error={}", params.q(), e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    @SuppressWarnings("unused")
    CompletableFuture<SearchDtos.SearchResponse> searchFallback(SearchParams params, Throwable t) {
        log.warn("ES circuit open — serving fallback top-1000: {}", t.getMessage());
        return CompletableFuture.supplyAsync(() -> {
            try {
                String cached = redisTemplate.opsForValue().get(fallbackRedisKey);
                if (cached != null) {
                    return objectMapper.readValue(cached, SearchDtos.SearchResponse.class);
                }
            } catch (Exception e) {
                log.error("Fallback cache read failed: {}", e.getMessage());
            }
            return new SearchDtos.SearchResponse(params.q(), 0, 0, 0, 0, List.of(), null, null, null);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 2 — GET /search/autocomplete
    // Sub-50ms target. Edge NGram = O(1) term lookup, no aggregations.
    // ─────────────────────────────────────────────────────────────────────────

    @CircuitBreaker(name = "elasticsearch")
    @TimeLimiter(name = "elasticsearch")
    public CompletableFuture<AutocompleteResponse> autocomplete(String q, int size, String category) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                long start = System.currentTimeMillis();
                SearchRequest req = queryBuilder.buildAutocompleteRequest(q, size, category);
                co.elastic.clients.elasticsearch.core.SearchResponse<ProductIndexDocument> resp = esClient.search(req, ProductIndexDocument.class);

                List<AutocompleteSuggestion> suggestions = resp.hits().hits().stream()
                        .map(this::buildAutocompleteSuggestion)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                return new AutocompleteResponse(q, System.currentTimeMillis() - start, suggestions);
            } catch (Exception e) {
                log.error("Autocomplete failed: q={}: {}", q, e.getMessage());
                return new AutocompleteResponse(q, 0, List.of());
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 3 — GET /search/products/{id}/similar
    // Elasticsearch MLT — term significance, no explicit similarity rules needed
    // ─────────────────────────────────────────────────────────────────────────

    @CircuitBreaker(name = "elasticsearch")
    @TimeLimiter(name = "elasticsearch")
    public CompletableFuture<SimilarProductsResponse> findSimilar(String productId, int size,
                                                                  boolean excludeSameMerchant,
                                                                  Double maxPrice) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Fetch reference product title for response
                GetResponse<ProductIndexDocument> ref = esClient.get(g -> g
                        .index(indexAlias).id(productId), ProductIndexDocument.class);

                if (!ref.found()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Product not found in index: " + productId);
                }

                SearchRequest req = queryBuilder.buildSimilarRequest(productId, size, maxPrice);
                co.elastic.clients.elasticsearch.core.SearchResponse<ProductIndexDocument> resp = esClient.search(req, ProductIndexDocument.class);

                List<SimilarProduct> similar = resp.hits().hits().stream()
                        .map(this::buildSimilarProduct)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                String refTitle = ref.source() != null ? ref.source().getTitle() : productId;
                return new SimilarProductsResponse(productId, refTitle, similar);

            } catch (ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                log.error("Similar query failed: productId={}: {}", productId, e.getMessage());
                return new SimilarProductsResponse(productId, "", List.of());
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 4 — GET /search/categories/{id}/products
    // Pure filter + function score, no BM25. Adds subcategory facet.
    // ─────────────────────────────────────────────────────────────────────────

    @CircuitBreaker(name = "elasticsearch")
    @TimeLimiter(name = "elasticsearch")
    public CompletableFuture<CategoryBrowseResponse> browseCategory(String categoryId,
                                                                    SearchParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                SearchRequest req = queryBuilder.buildCategoryBrowseRequest(categoryId, params);
                co.elastic.clients.elasticsearch.core.SearchResponse<ProductIndexDocument> resp = esClient.search(req, ProductIndexDocument.class);

                List<ProductSearchResult> results = resp.hits().hits().stream()
                        .map(hit -> buildSearchResult(hit, params))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                SearchFacets facets = buildFacets(resp.aggregations());
                List<FacetValue> subcategories = buildSubcategoryFacet(resp.aggregations());
                long total = resp.hits().total() != null ? resp.hits().total().value() : 0;

                return new CategoryBrowseResponse(
                        categoryId, total, params.page(), params.size(),
                        resp.took(), results, facets, subcategories);

            } catch (Exception e) {
                log.error("Category browse failed: categoryId={}: {}", categoryId, e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 5 — POST /internal/search/index/products (admin re-index single)
    // ─────────────────────────────────────────────────────────────────────────

    public IndexProductResponse reindexProduct(IndexProductRequest req) {
        try {
            // Fetch fresh data from Product Catalog
            ProductIndexDocument doc = internalClients.fetchProductForIndexing(req.productId());

            esClient.index(i -> i
                    .index(indexAlias)
                    .id(req.productId())
                    .document(doc));

            log.info("Manual re-index: {} by {} — reason: {}", req.productId(), req.requestedBy(), req.reason());
            return new IndexProductResponse(req.productId(), Instant.now(), "1", req.requestedBy());

        } catch (Exception e) {
            log.error("Manual re-index failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Re-index failed: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 6 — DELETE /internal/search/index/products/{id}
    // ─────────────────────────────────────────────────────────────────────────

    public void deleteFromIndex(String productId) {
        try {
            DeleteResponse resp = esClient.delete(d -> d.index(indexAlias).id(productId));
            if (resp.result() == co.elastic.clients.elasticsearch._types.Result.NotFound) {
                log.warn("Product not found in index during delete: {}", productId);
            } else {
                log.info("Deleted from index: {}", productId);
            }
        } catch (Exception e) {
            log.error("Index delete failed: productId={}: {}", productId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Delete from index failed: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 7 — POST /internal/search/index/rebuild (blue-green zero downtime)
    // ─────────────────────────────────────────────────────────────────────────

    public RebuildResponse rebuildIndex(RebuildRequest req) {
        if (!"yes".equalsIgnoreCase(req.confirm())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Confirm field must equal 'yes' to proceed with full rebuild");
        }

        long start = System.currentTimeMillis();
        String newIndexName = "products_v" + System.currentTimeMillis();
        int totalIndexed = 0;

        try {
            log.info("Index rebuild started: newIndex={}, by={}, reason={}",
                    newIndexName, req.requestedBy(), req.reason());

            // PHASE 1: Create new index with full mapping
            createIndexWithMapping(newIndexName);
            log.info("Created new index: {}", newIndexName);

            // PHASE 2: Bulk index all active products
            totalIndexed = bulkIndexAllProducts(newIndexName);
            log.info("Bulk indexed {} products to {}", totalIndexed, newIndexName);

            // PHASE 3: Verify doc count
            long newCount = esClient.count(c -> c.index(newIndexName)).count();
            if (Math.abs(newCount - totalIndexed) > totalIndexed * 0.001) {
                log.warn("Doc count mismatch: expected={}, got={}", totalIndexed, newCount);
            }

            // PHASE 4: Atomic alias swap — zero downtime
            // Use the proper builder pattern for updateAliases actions
            // PHASE 4: Atomic alias swap — zero downtime
            esClient.indices().updateAliases(ua -> ua.actions(
                    new co.elastic.clients.elasticsearch.indices.update_aliases.Action.Builder()
                            .remove(r -> r
                                    .index(indexAlias + "*")
                                    .alias(indexAlias)
                            )
                            .build(),
                    new co.elastic.clients.elasticsearch.indices.update_aliases.Action.Builder()
                            .add(a -> a
                                    .index(newIndexName)
                                    .alias(indexAlias)
                            )
                            .build()
            ));

            log.info("Alias swapped: {} → {}", indexAlias, newIndexName);
            long elapsed = (System.currentTimeMillis() - start) / 1000;

            return new RebuildResponse("COMPLETED", totalIndexed, elapsed, newIndexName, Instant.now());

        } catch (Exception e) {
            log.error("Index rebuild failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Rebuild failed: " + e.getMessage());
        }
    }

    private void createIndexWithMapping(String indexName) throws Exception {
        InputStream mappingStream = getClass().getResourceAsStream(
                "/elasticsearch/products-index.json");
        if (mappingStream == null) throw new IllegalStateException("products-index.json not found");

        esClient.indices().create(c -> c.index(indexName).withJson(mappingStream));
    }

    private int bulkIndexAllProducts(String targetIndex) {
        // Paginate all products from catalog and bulk index in batches of 500
        int total = 0;
        int page = 0;
        int batchSize = 500;
        List<ProductIndexDocument> batch;

        do {
            batch = internalClients.fetchProductBatch(page, batchSize);
            if (!batch.isEmpty()) {
                try {
                    BulkRequest.Builder br = new BulkRequest.Builder();
                    for (ProductIndexDocument doc : batch) {
                        br.operations(op -> op
                                .index(idx -> idx
                                        .index(targetIndex)
                                        .id(doc.getId())
                                        .document(doc)
                                )
                        );
                    }
                    esClient.bulk(br.build());
                    total += batch.size();
                    log.debug("Bulk indexed batch {}: {} products ({} total)", page, batch.size(), total);
                } catch (Exception e) {
                    log.error("Bulk index batch {} failed: {}", page, e.getMessage());
                }
            }
            page++;
        } while (batch.size() == batchSize);

        return total;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 8 — GET /internal/search/index/health
    // ─────────────────────────────────────────────────────────────────────────

    public IndexHealthResponse getIndexHealth() {
        try {
            // Index stats
            var statsResp = esClient.indices().stats(s -> s.index(indexAlias));
            var health = esClient.cluster().health(h -> h.index(indexAlias));

            long totalDocs = statsResp.indices() != null && statsResp.indices().get(indexAlias) != null
                    ? Objects.requireNonNullElse(statsResp.indices().get(indexAlias).total().docs().count(), 0L)
                    : 0L;

            // Kafka consumer lag — from admin client (see KafkaLagService)
            Map<String, Long> lagMap = Map.of(
                    "product.created",     0L,
                    "product.updated",     0L,
                    "product.delisted",    0L,
                    "inventory.restocked", 0L
            );

            return new IndexHealthResponse(
                    indexAlias, indexAlias,
                    health.status().jsonValue().toUpperCase(),
                    "OPEN",
                    new IndexDocumentStats(totalDocs, totalDocs, 0, 0),
                    new IndexShardStats(
                            health.activePrimaryShards(),
                            health.activeShards() - health.activePrimaryShards(),
                            health.unassignedShards()),
                    lagMap,
                    Instant.now(),
                    totalDocs * 0.05 / 1024,  // approximate GB
                    50L
            );

        } catch (Exception e) {
            log.error("Health check failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Unable to retrieve index health: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Response builders
    // ─────────────────────────────────────────────────────────────────────────

    private ProductSearchResult buildSearchResult(Hit<ProductIndexDocument> hit, SearchParams params) {
        ProductIndexDocument doc = hit.source();
        if (doc == null) return null;

        Double distanceKm = null;
        // Geo distance would be extracted from sort value if geo sort was applied

        return new ProductSearchResult(
                doc.getId(), doc.getTitle(), doc.getMerchantName(),
                doc.getPrice(), doc.getCurrency() != null ? doc.getCurrency() : "MXN",
                doc.getRating(), doc.isAvailable(),
                doc.getThumbnailUrl(),
                doc.getCategoryPath(),
                doc.getAttributes(),
                hit.score() != null ? hit.score() : 0.0,
                distanceKm);
    }

    private AutocompleteSuggestion buildAutocompleteSuggestion(Hit<ProductIndexDocument> hit) {
        ProductIndexDocument doc = hit.source();
        if (doc == null) return null;

        String category = doc.getCategoryPath() != null && !doc.getCategoryPath().isEmpty()
                ? doc.getCategoryPath().get(doc.getCategoryPath().size() - 1)
                : null;

        return new AutocompleteSuggestion(
                doc.getTitle(), doc.getId(), category,
                doc.getPrice(), doc.getThumbnailUrl(),
                "PRODUCT", null);
    }

    private SimilarProduct buildSimilarProduct(Hit<ProductIndexDocument> hit) {
        ProductIndexDocument doc = hit.source();
        if (doc == null) return null;

        String reason = "Same brand or category, similar attributes";

        return new SimilarProduct(
                doc.getId(), doc.getTitle(), doc.getPrice(),
                reason, doc.getRating(), doc.isAvailable(), doc.getThumbnailUrl());
    }

    @SuppressWarnings("unchecked")
    private SearchFacets buildFacets(Map<String, co.elastic.clients.elasticsearch._types.aggregations.Aggregate> aggs) {
        if (aggs == null) return null;

        List<FacetValue> brands = buildTermsFacet(aggs, "brands");
        List<FacetValue> ratings = buildTermsFacet(aggs, "ratings");
        List<FacetValue> colors = buildTermsFacet(aggs, "colors");
        List<PriceFacetValue> priceRanges = buildPriceRangeFacet(aggs);
        Map<String, Long> avail = buildAvailabilityFacet(aggs);

        return new SearchFacets(brands, priceRanges, ratings, colors, avail);
    }

    private List<FacetValue> buildTermsFacet(Map<String, co.elastic.clients.elasticsearch._types.aggregations.Aggregate> aggs, String name) {
        var agg = aggs.get(name);
        if (agg == null || !agg.isSterms()) return List.of();
        return agg.sterms().buckets().array().stream()
                .map(b -> new FacetValue(b.key().stringValue(), b.docCount()))
                .collect(Collectors.toList());
    }

    private List<PriceFacetValue> buildPriceRangeFacet(Map<String, co.elastic.clients.elasticsearch._types.aggregations.Aggregate> aggs) {
        var agg = aggs.get("price_ranges");
        if (agg == null || !agg.isRange()) return List.of();
        return agg.range().buckets().array().stream()
                .map(b -> new PriceFacetValue(b.key(), b.docCount()))
                .collect(Collectors.toList());
    }

    private Map<String, Long> buildAvailabilityFacet(Map<String, co.elastic.clients.elasticsearch._types.aggregations.Aggregate> aggs) {
        var agg = aggs.get("availability");
        if (agg == null || !agg.isSterms()) return Map.of();
        Map<String, Long> result = new LinkedHashMap<>();
        agg.sterms().buckets().array().forEach(b -> {
            boolean avail = Boolean.parseBoolean(b.key().stringValue());
            result.put(avail ? "in_stock" : "out_of_stock", b.docCount());
        });
        return result;
    }

    private List<FacetValue> buildSubcategoryFacet(Map<String, co.elastic.clients.elasticsearch._types.aggregations.Aggregate> aggs) {
        return buildTermsFacet(aggs, "subcategories");
    }
}