package io.tradeflow.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class CatalogDtos {

    private CatalogDtos() {}

    // ─────────────────────────────────────────────────────────────────────────
    // PRODUCT CREATE/UPDATE
    // ─────────────────────────────────────────────────────────────────────────

    public record PriceDto(
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotBlank String mode   // FIXED | TIERED | PROMOTIONAL
    ) {}


    public record CreateProductRequest(
            @NotBlank @JsonProperty("merchant_id") String merchantId,  // ← Acepta merchant_id del JSON
            @NotBlank @Size(min = 2, max = 300) String title,
            String description,
            @NotBlank @JsonProperty("category_id") String categoryId,  // ← También para category_id
            @Valid @NotNull PriceDto price,
            @JsonProperty("stock_ref") String stockRef,                // ← También para stock_ref
            Map<String, Object> attributes,
            List<String> tags
    ) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateProductResponse(
            String productId,
            String status,
            Instant createdAt,
            String searchIndexing
    ) {}

    public record UpdateProductRequest(
            String title,
            String description,
            String status,        // DRAFT→ACTIVE, ACTIVE→SUSPENDED
            Map<String, Object> attributes,
            List<String> tags
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // PRICING
    // ─────────────────────────────────────────────────────────────────────────

    public record PriceTierDto(
            @Min(1) int minQty,
            @DecimalMin("0.01") @DecimalMax("99.99") BigDecimal discountPct
    ) {}

    public record UpdatePriceRequest(
            @NotBlank String mode,          // FIXED | TIERED | PROMOTIONAL
            BigDecimal amount,              // FIXED
            BigDecimal baseAmount,          // TIERED
            List<@Valid PriceTierDto> tiers, // TIERED
            BigDecimal promotionalPrice,    // PROMOTIONAL
            BigDecimal originalPrice,       // PROMOTIONAL
            Instant campaignStart,          // PROMOTIONAL
            Instant campaignEnd,            // PROMOTIONAL
            String currency
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // PRODUCT RESPONSE (assembled from PG + MongoDB)
    // ─────────────────────────────────────────────────────────────────────────

    public record MerchantSummaryDto(
            String id,
            String name,
            Double rating
    ) {}

    public record PriceDetailDto(
            BigDecimal amount,
            String currency,
            String mode,
            String display,
            BigDecimal originalAmount,      // present when on_sale
            Integer discountPct
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductDetailResponse(
            String productId,
            String title,
            MerchantSummaryDto merchant,
            PriceDetailDto price,
            String status,
            Boolean stockAvailable,
            List<String> categoryPath,
            Map<String, Object> attributes,
            List<Map<String, Object>> mediaUrls,
            List<String> tags,
            Instant createdAt,
            Instant updatedAt
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // PRODUCT LIST (paginated)
    // ─────────────────────────────────────────────────────────────────────────

    public record ProductSummaryDto(
            String productId,
            String title,
            PriceDetailDto price,
            String status,
            String thumbnailUrl,
            String merchantId,
            Instant createdAt
    ) {}

    public record PagedProductResponse(
            List<ProductSummaryDto> products,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // DELIST
    // ─────────────────────────────────────────────────────────────────────────

    public record DelistProductRequest(
            @NotBlank String reason,
            String notes
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // MEDIA
    // ─────────────────────────────────────────────────────────────────────────

    public record MediaUploadResponse(
            String mediaUrl,
            String cdnUrl,
            String mediaType,
            int position
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // CATEGORY TREE
    // ─────────────────────────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record CategoryDto(
            String id,
            String name,
            String slug,
            List<String> requiredAttributes,
            List<CategoryDto> children
    ) {}

    public record CategoryTreeResponse(List<CategoryDto> categories) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ERROR
    // ─────────────────────────────────────────────────────────────────────────

    public record ErrorResponse(String error, String message, int status, Instant timestamp) {
        public static ErrorResponse of(String error, String message, int status) {
            return new ErrorResponse(error, message, status, Instant.now());
        }
    }
}
