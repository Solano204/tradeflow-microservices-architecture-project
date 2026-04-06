package io.tradeflow.search.document;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * ProductIndexDocument — the Elasticsearch document structure.
 *
 * Mapped to the "products" index. The full mapping (including edge NGram
 * analyzers, synonym analyzer, geo_point) is defined in
 * src/main/resources/elasticsearch/products-index.json
 *
 * Key design decisions from spec:
 *
 *   title: text with 3 sub-fields:
 *     - title (synonym_analyzer) — "mobile phone" finds "smartphone"
 *     - title.keyword (exact) — for sorting and deduplication
 *     - title.autocomplete (edge_ngram) — "Sams" finds "Samsung Galaxy S24"
 *
 *   category_path: keyword array — enables faceted aggregation per path segment
 *     ["Electronics", "Phones", "Android"] → facet by any level
 *
 *   attributes: object with keyword fields — brand/color/size etc.
 *     keyword type → exact filter ("brand: Samsung") + facet aggregation
 *
 *   location: geo_point — enables geo-boost in function_score
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProductIndexDocument {

    /** Elasticsearch document ID — matches product_id from Product Catalog */
    private String id;

    /**
     * title has 3 simultaneous representations in ES mapping:
     *   .text (synonym_analyzer) — full-text search with synonym expansion
     *   .keyword — exact match, sort
     *   .autocomplete (edge_ngram) — prefix suggestions
     */
    private String title;

    private String description;

    @JsonProperty("merchant_name")
    private String merchantName;

    @JsonProperty("merchant_id")
    private String merchantId;

    /**
     * Array of path segments: ["Electronics", "Phones", "Android"]
     * keyword type → terms aggregation gives facet counts per level.
     * A filter on "Electronics" matches all products in the Electronics subtree.
     */
    @JsonProperty("category_path")
    private List<String> categoryPath;

    @JsonProperty("category_id")
    private String categoryId;

    private double price;
    private String currency;

    /** Computed average from reviews — updated on review.created events */
    private float rating;

    @JsonProperty("review_count")
    private int reviewCount;

    /** Cumulative orders — drives the sales_count function score boost */
    @JsonProperty("sales_count")
    @Builder.Default
    private int salesCount = 0;

    /** Updated by inventory.restocked Kafka consumer — only field changed on restock */
    private boolean available;

    @JsonProperty("thumbnail_url")
    private String thumbnailUrl;

    /**
     * Flexible key-value attributes: brand, color, size, material, storage_gb, etc.
     * keyword type on all values → exact filter + facet aggregation.
     * Dynamic mapping handles new attribute keys without schema changes.
     */
    private Map<String, Object> attributes;

    /**
     * Merchant's geo coordinates — used in gaussian decay function for geo-boost.
     * At 50km: no decay. At 200km: score × 0.5. Below asymptote: × 0.1.
     * Format: { "lat": 19.4326, "lon": -99.1332 }
     */
    private GeoPoint location;

    @JsonProperty("created_at")
    private Instant createdAt;

    /**
     * Days since listing — recency boost: listedDaysAgo ≤ 7 → score × 1.3.
     * Updated by a daily @Scheduled job (or approximate via Elasticsearch scripted update).
     */
    @JsonProperty("listed_days_ago")
    @Builder.Default
    private int listedDaysAgo = 0;

    /** Inner record for geo_point type */
    public record GeoPoint(double lat, double lon) {}
}
