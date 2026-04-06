package io.tradeflow.search.service;

import co.elastic.clients.elasticsearch._types.*;
import co.elastic.clients.elasticsearch._types.aggregations.*;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.search.*;
import co.elastic.clients.json.JsonData;
import io.tradeflow.search.document.ProductIndexDocument;
import io.tradeflow.search.dto.SearchDtos.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SearchQueryBuilder — translates business search params into Elasticsearch DSL.
 *
 * The core architecture decisions implemented here:
 *
 * 1. Filter context vs query context:
 *    - Text matching in QUERY context → affects BM25 score
 *    - Category, price, brand, availability in FILTER context → binary, CACHED
 *    This means the same price/brand filter across different text queries
 *    reuses the cached filter bitset — dramatically better performance.
 *
 * 2. function_score envelope:
 *    BM25 score × recency(1.3) × sales_velocity(1.2) × rating(1.1) × geo(1.15 optional)
 *    score_mode: multiply, boost_mode: multiply
 *
 * 3. Aggregations in same request as results — one round-trip gives both
 *    ranked products AND facet panel data (brands, price ranges, ratings, colors).
 *
 * 4. Edge NGram for autocomplete — pre-indexed tokens, O(1) term lookup, ~12ms.
 *    Fundamentally different query shape from full-text search.
 *
 * 5. More Like This for similar products — term significance analysis,
 *    no explicit rules needed — the index's language captures similarity.
 */
@Component
@Slf4j
public class SearchQueryBuilder {

    static final String INDEX = "products";
    static final String AUTOCOMPLETE_FIELD = "title.autocomplete";

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 1 — Full product search query
    // ─────────────────────────────────────────────────────────────────────────

    public SearchRequest buildSearchRequest(SearchParams params) {

        // STEP 1: Core text query with synonym expansion (or matchAll if no query)
        Query textQuery = params.hasQuery()
                ? buildMultiMatchQuery(params.q())
                : new Query.Builder().matchAll(m -> m).build();

        // STEP 2: Filter clauses — binary yes/no, cached, no score impact
        BoolQuery.Builder filters = new BoolQuery.Builder();
        if (params.hasCategory())
            filters.filter(f -> f.term(t -> t.field("category_path").value(params.category())));
        if (params.hasPriceRange())
            filters.filter(f -> f.range(r -> buildPriceRange(r, params)));
        if (params.hasBrands() && !params.brand().isEmpty())
            filters.filter(f -> f.terms(t -> t.field("attributes.brand")
                    .terms(tv -> tv.value(params.brand().stream()
                            .map(FieldValue::of).toList()))));
        if (params.hasColor())
            filters.filter(f -> f.term(t -> t.field("attributes.color").value(params.color())));
        if (params.hasMinRating())
            filters.filter(f -> f.range(r -> r.field("rating").gte(JsonData.of(params.rating()))));
        if (params.available())
            filters.filter(f -> f.term(t -> t.field("available").value(true)));

        // STEP 3: Combine text + filters
        Query mainQuery = new Query.Builder().bool(b -> b
                .must(textQuery)
                .filter(filters.build().filter())
        ).build();

        // STEP 4: function_score with recency, sales, rating, and optional geo boosts
        Query functionScore = buildFunctionScore(mainQuery, params);

        // STEP 5: Build request with aggregations
        return SearchRequest.of(s -> s
                .index(INDEX)
                .query(functionScore)
                .from(params.page() * params.size())
                .size(params.size())
                .sort(buildSort(params.sort()))
                .aggregations(buildSearchAggregations())
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 2 — Autocomplete query (edge NGram)
    //
    // Uses title.autocomplete field (pre-indexed edge NGrams).
    // "Sams" at query time → standard tokenizer → "sams"
    // Inverted index lookup for "sams" → instant match → O(1)
    // No aggregations (saves 5-10ms per request toward the 50ms budget).
    // Only returns 6 fields (minimize network transfer for speed).
    // ─────────────────────────────────────────────────────────────────────────

    public SearchRequest buildAutocompleteRequest(String prefix, int size, String category) {
        BoolQuery.Builder query = new BoolQuery.Builder()
                .must(m -> m.match(mt -> mt
                        .field(AUTOCOMPLETE_FIELD)
                        .query(prefix)
                        .analyzer("autocomplete_search_analyzer")))
                .filter(f -> f.term(t -> t.field("available").value(true)));

        if (category != null && !category.isBlank())
            query.filter(f -> f.term(t -> t.field("category_path").value(category)));

        return SearchRequest.of(s -> s
                .index(INDEX)
                .query(q -> q.bool(query.build()))
                .size(size)
                // Only fetch fields needed — minimize response payload for speed
                .source(src -> src.filter(f -> f.includes(
                        "id", "title", "price", "category_path", "thumbnail_url", "sales_count"
                )))
                // Most popular completions first
                .sort(so -> so.field(f -> f.field("sales_count").order(SortOrder.Desc)))
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 3 — More Like This (MLT) query
    //
    // ES finds the most distinctive terms in the reference document
    // (high TF in this doc, low DF across corpus = significant term)
    // then queries for documents containing those terms.
    // No explicit "similar = same brand" rule needed — the index captures it.
    // ─────────────────────────────────────────────────────────────────────────

    public SearchRequest buildSimilarRequest(String productId, int size, Double maxPrice) {
        // More Like This — term significance against reference document
        Query mlt = new Query.Builder().moreLikeThis(m -> m
                .fields("title", "description", "attributes.brand")
                .like(l -> l.document(d -> d.index(INDEX).id(productId)))
                .minTermFreq(1)        // term must appear ≥ 1x in reference
                .maxQueryTerms(20)     // use top 20 most significant terms
                .minDocFreq(3)         // ignore terms in < 3 docs (too rare/noisy)
        ).build();

        BoolQuery.Builder query = new BoolQuery.Builder()
                .must(mlt)
                .mustNot(mn -> mn.term(t -> t.field("id").value(productId)))  // exclude self
                .filter(f -> f.term(t -> t.field("available").value(true)));

        if (maxPrice != null)
            query.filter(f -> f.range(r -> r.field("price").lte(JsonData.of(maxPrice))));

        return SearchRequest.of(s -> s
                .index(INDEX)
                .query(q -> q.bool(query.build()))
                .size(size)
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 4 — Category browse
    //
    // No text query — pure filter + function score.
    // Difference from search: no BM25, just function score boosts.
    // Default sort: best_selling (sales_count DESC) not relevance.
    // Adds subcategory facet to show path breakdown within this category.
    // ─────────────────────────────────────────────────────────────────────────

    public SearchRequest buildCategoryBrowseRequest(String categoryId, SearchParams params) {
        BoolQuery.Builder filters = new BoolQuery.Builder()
                .filter(f -> f.term(t -> t.field("category_id").value(categoryId)))
                .filter(f -> f.term(t -> t.field("available").value(true)));

        if (params.hasPriceRange())
            filters.filter(f -> f.range(r -> buildPriceRange(r, params)));
        if (params.hasBrands() && !params.brand().isEmpty())
            filters.filter(f -> f.terms(t -> t.field("attributes.brand")
                    .terms(tv -> tv.value(params.brand().stream()
                            .map(FieldValue::of).toList()))));

        Query mainQuery = new Query.Builder().bool(filters.build()).build();
        Query ranked = buildFunctionScore(mainQuery, params);

        String sort = params.sort() != null ? params.sort() : "best_selling";

        return SearchRequest.of(s -> s
                .index(INDEX)
                .query(ranked)
                .from(params.page() * params.size())
                .size(params.size())
                .sort(buildSort(sort))
                .aggregations(buildSearchAggregations())
                .aggregations("subcategories", a -> a.terms(t -> t
                        .field("category_path").size(20)))
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // function_score wrapper — recency × sales × rating × geo boosts
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
// function_score wrapper — recency × sales × rating × geo boosts
// ─────────────────────────────────────────────────────────────────────────

    private Query buildFunctionScore(Query query, SearchParams params) {
        var functions = new java.util.ArrayList<FunctionScore>();

        // 1. Recency boost
        functions.add(FunctionScore.of(f -> f
                .filter(fl -> fl.range(r -> r.field("listed_days_ago").lte(JsonData.of(7.0))))
                .weight(1.3)
        ));

        // 2. Sales velocity boost
        functions.add(FunctionScore.of(f -> f
                .fieldValueFactor(fvf -> fvf
                        .field("sales_count")
                        .factor(0.0001)
                        .modifier(FieldValueFactorModifier.Log1p)
                        .missing(1.0)
                )
                .weight(1.2)
        ));

        // 3. Quality boost
        functions.add(FunctionScore.of(f -> f
                .filter(fl -> fl.range(r -> r.field("rating").gte(JsonData.of(4.5))))
                .weight(1.1)
        ));

        // 4. Geo decay: FIXED - use placement() instead of origin()
        if (params != null && params.hasGeoLocation()) {
            functions.add(FunctionScore.of(f -> f
                    .gauss(g -> g
                            .field("location")
                            .placement(p -> p  // FIX: Use placement() not origin()
                                    .origin(JsonData.of(params.lat() + "," + params.lon()))
                                    .scale(JsonData.of("50km"))
                                    .offset(JsonData.of("200km"))
                                    .decay(0.1)
                            )
                    )
                    .weight(1.15)
            ));
        }

        return Query.of(q -> q.functionScore(fs -> fs
                .query(query)
                .functions(functions)
                .scoreMode(FunctionScoreMode.Multiply)
                .boostMode(FunctionBoostMode.Multiply)
        ));
    }

    private java.util.Map<String, Aggregation> buildSearchAggregations() {
        java.util.Map<String, Aggregation> aggs = new java.util.LinkedHashMap<>();

        aggs.put("brands", Aggregation.of(a -> a.terms(t -> t.field("attributes.brand").size(20))));

        // FIX: Use double values directly with proper builder pattern
        aggs.put("price_ranges", Aggregation.of(a -> a.range(r -> r
                .field("price")
                .ranges(
                        AggregationRange.of(rg -> rg.to(String.valueOf(5000.0)).key("Under $5,000")),
                        AggregationRange.of(rg -> rg.from(String.valueOf(5000.0)).to(String.valueOf(10000.0)).key("$5,000-$10,000")),
                        AggregationRange.of(rg -> rg.from(String.valueOf(10000.0)).to(String.valueOf(20000.0)).key("$10,000-$20,000")),
                        AggregationRange.of(rg -> rg.from(String.valueOf(20000.0)).key("Over $20,000"))
                )
        )));

        aggs.put("ratings", Aggregation.of(a -> a.terms(t -> t.field("rating").size(5))));

        return aggs;
    }
    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Query buildMultiMatchQuery(String q) {
        return new Query.Builder().multiMatch(m -> m
                .query(q)
                // title = 3x weight, merchant_name = 1.5x, description = 1x
                .fields("title^3", "description^1", "merchant_name^1.5")
                .type(TextQueryType.BestFields)
                .minimumShouldMatch("75%")  // 75% of terms must match
        ).build();
    }

    private RangeQuery.Builder buildPriceRange(RangeQuery.Builder r, SearchParams params) {
        r.field("price");
        if (params.minPrice() != null) r.gte(JsonData.of(params.minPrice()));
        if (params.maxPrice() != null) r.lte(JsonData.of(params.maxPrice()));
        return r;
    }

    private List<SortOptions> buildSort(String sort) {
        return switch (sort != null ? sort : "relevance") {
            case "price_asc"   -> List.of(SortOptions.of(s -> s.field(f -> f.field("price").order(SortOrder.Asc))));
            case "price_desc"  -> List.of(SortOptions.of(s -> s.field(f -> f.field("price").order(SortOrder.Desc))));
            case "newest"      -> List.of(SortOptions.of(s -> s.field(f -> f.field("created_at").order(SortOrder.Desc))));
            case "best_selling"-> List.of(SortOptions.of(s -> s.field(f -> f.field("sales_count").order(SortOrder.Desc))));
            default            -> List.of(SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc))));
        };
    }
}