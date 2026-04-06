package io.tradeflow.catalog.controller;

import io.micrometer.tracing.Tracer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tradeflow.catalog.dto.CatalogDtos.*;
import io.tradeflow.catalog.service.CategoryService;
import io.tradeflow.catalog.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;

/**
 * CatalogController — REST endpoints for the Product Catalog.
 *
 * TRACING NOTES:
 * - Spring MVC auto-creates an HTTP span for every inbound request via
 *   Micrometer's ObservationFilter. No configuration needed.
 * - We inject Tracer only to add BUSINESS TAGS to the HTTP span so you
 *   can search in Zipkin by merchantId, productId, operation, etc.
 * - The traceId is automatically propagated to all downstream operations:
 *   JPA (PostgreSQL), MongoDB, Redis, Kafka producer, and outbound HTTP.
 * - The traceId also appears in every log line via MDC (traceId=%X{traceId}).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Product Catalog", description = "Product listings, pricing, categories, and media")
public class CatalogController {

    private final ProductService  productService;
    private final CategoryService categoryService;
    private final Tracer          tracer; // ✅ for adding business tags to the HTTP span

    // ─────────────────────────────────────────────────────────────────────────
    // POST /products — Create product listing
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MERCHANT')")
    @Operation(summary = "Create product listing")
    public CreateProductResponse createProduct(
            @Valid @RequestBody CreateProductRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        String jwtMerchantId = jwt.getClaimAsString("merchant_id");
        if (!jwtMerchantId.equals(request.merchantId())) {
            throw new AccessDeniedException("merchant_id mismatch");
        }

        // ✅ Tag the HTTP span with business context
        tagCurrentSpan("POST /products", jwtMerchantId, null, null);

        return productService.createProduct(request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /products/{id} — Get product detail
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/products/{id}")
    @Operation(summary = "Get product detail", security = @SecurityRequirement(name = "bearerAuth"))
    public ProductDetailResponse getProduct(@PathVariable String id) {

        // ✅ Tag the HTTP span
        tagCurrentSpan("GET /products/{id}", null, id, null);

        return productService.getProduct(id);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUT /products/{id} — Update product
    // ─────────────────────────────────────────────────────────────────────────

    @PutMapping("/products/{id}")
    @PreAuthorize("hasAnyRole('MERCHANT', 'ADMIN')")
    @Operation(summary = "Update product", security = @SecurityRequirement(name = "bearerAuth"))
    public void updateProduct(
            @PathVariable String id,
            @RequestBody UpdateProductRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        String jwtMerchantId = jwt.getClaimAsString("merchant_id");
        tagCurrentSpan("PUT /products/{id}", jwtMerchantId, id, null);
        productService.updateProduct(id, request, jwtMerchantId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUT /products/{id}/price — Update product pricing
    // ─────────────────────────────────────────────────────────────────────────

    @PutMapping("/products/{id}/price")
    @PreAuthorize("hasAnyRole('MERCHANT', 'ADMIN')")
    @Operation(summary = "Update product pricing", security = @SecurityRequirement(name = "bearerAuth"))
    public void updatePrice(
            @PathVariable String id,
            @Valid @RequestBody UpdatePriceRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        String merchantId = jwt.getClaimAsString("merchant_id");
        tagCurrentSpan("PUT /products/{id}/price", merchantId, id, request.mode());
        productService.updatePrice(id, request, merchantId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /products/{id}/delist — Delist product
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/products/{id}/delist")
    @PreAuthorize("hasAnyRole('MERCHANT', 'ADMIN')")
    @Operation(summary = "Delist product", security = @SecurityRequirement(name = "bearerAuth"))
    public void delistProduct(
            @PathVariable String id,
            @Valid @RequestBody DelistProductRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        String requesterId = jwt.getSubject();
        boolean isAdmin = jwt.getClaimAsStringList("roles") != null &&
                jwt.getClaimAsStringList("roles").contains("ADMIN");

        tagCurrentSpan("POST /products/{id}/delist", requesterId, id, request.reason());
        productService.delistProduct(id, request, requesterId, isAdmin);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /products/{id}/media — Upload product media
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping(value = "/products/{id}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MERCHANT')")
    @Operation(summary = "Upload product media", security = @SecurityRequirement(name = "bearerAuth"))
    public MediaUploadResponse uploadMedia(
            @PathVariable String id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "media_type", defaultValue = "IMAGE_GALLERY") String mediaType,
            @RequestParam(value = "position", defaultValue = "0") int position,
            @AuthenticationPrincipal Jwt jwt) {

        String merchantId = jwt.getClaimAsString("merchant_id");
        tagCurrentSpan("POST /products/{id}/media", merchantId, id, mediaType);
        return productService.uploadMedia(id, file, mediaType, position, merchantId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /categories — Get full category tree
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/categories")
    @Operation(summary = "Get full category tree")
    public CategoryTreeResponse getCategories() {
        tagCurrentSpan("GET /categories", null, null, null);
        return categoryService.getCategoryTree();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /categories/{id}/products — Browse products by category
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/categories/{id}/products")
    @Operation(summary = "Browse products by category")
    public PagedProductResponse getProductsByCategory(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice) {

        tagCurrentSpan("GET /categories/{id}/products", null, null, id);
        return productService.getProductsByCategory(id, page, size, sort, minPrice, maxPrice);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /merchants/{id}/products — List merchant's products
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/merchants/{id}/products")
    @PreAuthorize("hasAnyRole('MERCHANT', 'ADMIN')")
    @Operation(summary = "List merchant's products", security = @SecurityRequirement(name = "bearerAuth"))
    public PagedProductResponse getMerchantProducts(
            @PathVariable String id,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal Jwt jwt) {

        String requesterId = jwt.getSubject();
        boolean isAdmin = jwt.getClaimAsStringList("roles") != null &&
                jwt.getClaimAsStringList("roles").contains("ADMIN");

        tagCurrentSpan("GET /merchants/{id}/products", id, null, status);
        return productService.getMerchantProducts(id, status, page, size, requesterId, isAdmin);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /internal/campaigns/tick — Manual campaign trigger
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/internal/campaigns/tick")
    @PreAuthorize("hasRole('ADMIN') or @internalRequestValidator.isInternalRequest(request)")
    @Operation(summary = "Trigger campaign pricing tick (admin/internal)")
    public void triggerCampaignTick() {
        tagCurrentSpan("POST /internal/campaigns/tick", null, null, "manual");
        productService.tickCampaigns();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper — adds business tags to the current (auto-created) HTTP span
    // ─────────────────────────────────────────────────────────────────────────

    private void tagCurrentSpan(String operation, String merchantId, String productId, String extra) {
        var span = tracer.currentSpan();
        if (span == null) return;

        span.tag("catalog.http.operation", operation);
        if (merchantId != null) span.tag("catalog.merchant_id", merchantId);
        if (productId  != null) span.tag("catalog.product_id",  productId);
        if (extra      != null) span.tag("catalog.extra",        extra);
    }
}