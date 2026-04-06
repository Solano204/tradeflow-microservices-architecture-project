package io.tradeflow.catalog.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MongoDB presentation layer for product details.
 *
 * PostgreSQL owns: price, status, stock, merchant_id, category_id (source of truth)
 * MongoDB owns:    attributes (varies by category), description, media, tags, SEO slug
 *
 * No schema migrations needed — Electronics gets connector_type, Clothing gets sizes[],
 * Food gets allergens. The category_id is the implicit schema discriminator.
 */
@Document(collection = "product_details")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductDetail {

    @Id
    private String id;

    @Indexed(unique = true)
    private String productId;

    private String categoryId;

    private String merchantId;

    @Indexed
    private String title;           // denormalized for search suggestions

    private String description;

    private String seoSlug;

    /**
     * Flexible attributes — completely varies by category.
     * Electronics: { brand, model, storage_gb, ram_gb, connector_type, os, warranty_months }
     * Clothing:    { brand, sizes: [], colors: [], material, care_instructions }
     * Food:        { brand, allergens: [], expiry_date, weight_g, certifications: [] }
     * No schema migration needed when adding new categories.
     */
    private Map<String, Object> attributes;

    /**
     * Media array — stored in MongoDB because it's variable in count and presentation-only.
     * Each entry: { url, cdnUrl, mediaType, position }
     * In PostgreSQL this would require a product_media table + join on every read.
     */
    @Builder.Default
    private List<Map<String, Object>> mediaUrls = new ArrayList<>();

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;
}
