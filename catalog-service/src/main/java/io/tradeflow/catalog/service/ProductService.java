package io.tradeflow.catalog.service;

import io.micrometer.observation.annotation.Observed;
import io.micrometer.tracing.Tracer;
import io.tradeflow.catalog.dto.CatalogDtos.*;
import io.tradeflow.catalog.entity.*;
import io.tradeflow.catalog.entity.Product.PriceMode;
import io.tradeflow.catalog.entity.Product.ProductStatus;
import io.tradeflow.catalog.repository.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {

    private final ProductRepository productRepo;
    private final ProductDetailRepository detailRepo;
    private final CategoryRepository categoryRepo;
    private final PricingRuleRepository pricingRuleRepo;
    private final CatalogOutboxRepository outboxRepo;
    private final CatalogRedisService redisService;
    private final MediaUploadService mediaUploadService;
    private final Tracer tracer; // ✅ injected to tag spans with business context

    private static final int CACHE_WARMUP_SIZE = 1000;
    private static final int CACHE_WARMUP_BATCH = 50;

    // ─────────────────────────────────────────────────────────────────────────
    // STARTUP — Pre-warm top 1000 hot products into Redis
    // ─────────────────────────────────────────────────────────────────────────

    @PostConstruct
    public void warmCache() {
        log.info("Cache warm-up starting: loading top {} products", CACHE_WARMUP_SIZE);
        long start = System.currentTimeMillis();

        try {
            List<Product> hotProducts = productRepo.findTopBySalesVolume(
                    PageRequest.of(0, CACHE_WARMUP_SIZE));

            List<List<Product>> batches = partition(hotProducts, CACHE_WARMUP_BATCH);
            int warmed = 0;

            for (List<Product> batch : batches) {
                List<String> ids = batch.stream().map(Product::getId).toList();
                Map<String, ProductDetail> detailMap = detailRepo.findByProductIdIn(ids)
                        .stream().collect(Collectors.toMap(ProductDetail::getProductId, d -> d));

                for (Product p : batch) {
                    ProductDetail detail = detailMap.get(p.getId());
                    if (detail != null) {
                        ProductDetailResponse response = assemble(p, detail);
                        redisService.cacheProduct(p.getId(), response, 3600);
                        warmed++;
                    }
                }
            }

            long elapsed = System.currentTimeMillis() - start;
            log.info("Cache warm-up complete: {} products loaded in {}ms", warmed, elapsed);

        } catch (Exception e) {
            log.warn("Cache warm-up failed (service will start cold): {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 1 — POST /products
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @Observed creates a named child span for this method.
     * The span name "catalog.product.create" appears in Zipkin under the HTTP span.
     * All DB operations inside (JPA save, MongoDB save) appear as grandchildren.
     */
    @Observed(name = "catalog.product.create", contextualName = "create-product")
    @Transactional
    public CreateProductResponse createProduct(CreateProductRequest req) {
        if (!categoryRepo.existsById(req.categoryId())) {
            throw new BadRequestException("Unknown category: " + req.categoryId());
        }

        String productId = "prd_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        // ✅ Tag the current span with business context
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("catalog.product_id",   productId);
            span.tag("catalog.merchant_id",  req.merchantId());
            span.tag("catalog.category_id",  req.categoryId());
            span.tag("catalog.price_mode",   req.price().mode());
            span.tag("catalog.operation",    "create");
        }

        Product product = Product.builder()
                .id(productId)
                .merchantId(req.merchantId())
                .title(req.title())
                .categoryId(req.categoryId())
                .price(req.price().amount())
                .currency(req.price().currency())
                .priceMode(PriceMode.valueOf(req.price().mode()))
                .stockRef(req.stockRef())
                .status(ProductStatus.DRAFT)
                .build();

        CatalogOutbox outbox = CatalogOutbox.builder()
                .eventType("product.created")
                .payload(buildProductPayload(productId, req))
                .aggregateId(productId)
                .build();

        // STEP 1: PostgreSQL — transactional (auto-traced by datasource-micrometer)
        productRepo.save(product);
        outboxRepo.save(outbox);

        // STEP 2: MongoDB — presentation layer (auto-traced by Spring Data MongoDB)
        try {
            ProductDetail detail = ProductDetail.builder()
                    .productId(productId)
                    .categoryId(req.categoryId())
                    .merchantId(req.merchantId())
                    .title(req.title())
                    .description(req.description())
                    .seoSlug(buildSlug(req.title()))
                    .attributes(req.attributes() != null ? req.attributes() : new HashMap<>())
                    .tags(req.tags() != null ? req.tags() : new ArrayList<>())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            detailRepo.save(detail);
        } catch (Exception e) {
            if (span != null) span.error(e);
            log.error("MongoDB write failed for productId={} — product created in PG without attributes: {}",
                    productId, e.getMessage());
        }

        log.info("[traceId={}] Product created: productId={}, merchantId={}",
                span != null ? span.context().traceId() : "none", productId, req.merchantId());

        return new CreateProductResponse(productId, "DRAFT", product.getCreatedAt(), "PENDING");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 2 — GET /products/{id}
    // Cache-Aside: Redis → (parallel PG + MongoDB) → assemble → re-cache
    // ─────────────────────────────────────────────────────────────────────────

    @Observed(name = "catalog.product.get", contextualName = "get-product")
    public ProductDetailResponse getProduct(String productId) {
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("catalog.product_id", productId);
            span.tag("catalog.operation",  "get");
        }

        // 1. Redis (auto-traced via Lettuce + Micrometer)
        ProductDetailResponse cached = redisService.getProduct(productId);
        if (cached != null) {
            if (span != null) span.tag("catalog.cache", "hit");
            return cached;
        }

        if (span != null) span.tag("catalog.cache", "miss");

        // 2. Cache miss → parallel fetch from PG + MongoDB
        Product product;
        ProductDetail detail;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Product> pgFuture = executor.submit(() ->
                    productRepo.findById(productId)
                            .orElseThrow(() -> new NotFoundException("Product not found: " + productId)));

            Future<ProductDetail> mongoFuture = executor.submit(() ->
                    detailRepo.findByProductId(productId).orElse(null));

            product = pgFuture.get(5, TimeUnit.SECONDS);
            detail  = mongoFuture.get(5, TimeUnit.SECONDS);

        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof NotFoundException nfe) throw nfe;
            throw new RuntimeException("Failed to fetch product: " + productId, cause);
        } catch (Exception e) {
            throw new RuntimeException("Timeout fetching product: " + productId, e);
        }

        ProductDetailResponse response = assemble(product, detail);
        redisService.cacheProduct(productId, response, 300);

        return response;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 3 — PUT /products/{id}
    // ─────────────────────────────────────────────────────────────────────────

    @Observed(name = "catalog.product.update", contextualName = "update-product")
    @Transactional
    public void updateProduct(String productId, UpdateProductRequest req, String merchantId) {
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("catalog.product_id",  productId);
            span.tag("catalog.merchant_id", merchantId);
            span.tag("catalog.operation",   "update");
        }

        Product product = findOwnedProduct(productId, merchantId);

        if (req.status() != null) {
            ProductStatus target = ProductStatus.valueOf(req.status());
            if (!product.canTransitionTo(target)) {
                throw new BadRequestException(
                        "Illegal status transition: " + product.getStatus() + " → " + target);
            }
            if (span != null) span.tag("catalog.status_transition",
                    product.getStatus().name() + "->" + target.name());
            product.setStatus(target);
        }

        if (req.title() != null) product.setTitle(req.title());

        CatalogOutbox outbox = CatalogOutbox.builder()
                .eventType("product.updated")
                .payload(Map.of(
                        "product_id", productId,
                        "merchant_id", merchantId,
                        "changes", Map.of(
                                "title",  req.title() != null ? req.title() : "",
                                "status", product.getStatus().name()
                        ),
                        "timestamp", Instant.now().toString()
                ))
                .aggregateId(productId)
                .build();

        productRepo.save(product);
        outboxRepo.save(outbox);

        detailRepo.findByProductId(productId).ifPresent(detail -> {
            if (req.title()       != null) detail.setTitle(req.title());
            if (req.description() != null) detail.setDescription(req.description());
            if (req.attributes()  != null) {
                Map<String, Object> merged = new HashMap<>(
                        detail.getAttributes() != null ? detail.getAttributes() : Map.of());
                merged.putAll(req.attributes());
                detail.setAttributes(merged);
            }
            if (req.tags() != null) detail.setTags(req.tags());
            detail.setUpdatedAt(Instant.now());
            detailRepo.save(detail);
        });

        redisService.evict(productId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 4 — PUT /products/{id}/price
    // ─────────────────────────────────────────────────────────────────────────

    @Observed(name = "catalog.product.price", contextualName = "update-price")
    @Transactional
    public void updatePrice(String productId, UpdatePriceRequest req, String merchantId) {
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("catalog.product_id",  productId);
            span.tag("catalog.merchant_id", merchantId);
            span.tag("catalog.price_mode",  req.mode());
            span.tag("catalog.operation",   "update-price");
        }

        Product product  = findOwnedProduct(productId, merchantId);
        PriceMode mode   = PriceMode.valueOf(req.mode());
        BigDecimal oldPrice = product.getPrice();

        switch (mode) {
            case FIXED -> {
                validateFixed(req);
                product.setPrice(req.amount());
                product.setCurrency(req.currency() != null ? req.currency() : product.getCurrency());
                product.setPriceMode(PriceMode.FIXED);
                product.setPromotionalPrice(null);
                product.setOriginalPrice(null);
                product.setPromotionalStart(null);
                product.setPromotionalEnd(null);
            }
            case TIERED -> {
                validateTiered(req);
                product.setPrice(req.baseAmount());
                product.setPriceMode(PriceMode.TIERED);
            }
            case PROMOTIONAL -> {
                validatePromotional(req);
                product.setPrice(req.originalPrice());
                product.setOriginalPrice(req.originalPrice());
                product.setPromotionalPrice(req.promotionalPrice());
                product.setPromotionalStart(req.campaignStart());
                product.setPromotionalEnd(req.campaignEnd());
                product.setPriceMode(PriceMode.PROMOTIONAL);
            }
        }

        pricingRuleRepo.deleteByProductId(productId);
        if (mode == PriceMode.TIERED && req.tiers() != null) {
            for (PriceTierDto tier : req.tiers()) {
                pricingRuleRepo.save(PricingRule.builder()
                        .productId(productId)
                        .ruleType("TIER")
                        .tierMinQty(tier.minQty())
                        .tierDiscountPct(tier.discountPct())
                        .build());
            }
        }

        CatalogOutbox outbox = CatalogOutbox.builder()
                .eventType("product.price.changed")
                .payload(Map.of(
                        "product_id", productId,
                        "merchant_id", merchantId,
                        "old_price",  oldPrice.toString(),
                        "new_price",  product.getPrice().toString(),
                        "currency",   product.getCurrency(),
                        "mode",       mode.name(),
                        "timestamp",  Instant.now().toString()
                ))
                .aggregateId(productId)
                .build();

        productRepo.save(product);
        outboxRepo.save(outbox);
        redisService.evict(productId);

        log.info("[traceId={}] Price updated: productId={}, mode={}, newPrice={}",
                span != null ? span.context().traceId() : "none",
                productId, mode, product.getPrice());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 5 — POST /products/{id}/delist
    // ─────────────────────────────────────────────────────────────────────────

    @Observed(name = "catalog.product.delist", contextualName = "delist-product")
    @Transactional
    public void delistProduct(String productId, DelistProductRequest req,
                              String requesterId, boolean isAdmin) {
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("catalog.product_id",  productId);
            span.tag("catalog.requester_id", requesterId);
            span.tag("catalog.is_admin",    String.valueOf(isAdmin));
            span.tag("catalog.delist_reason", req.reason());
            span.tag("catalog.operation",   "delist");
        }

        Product product = productRepo.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product not found: " + productId));

        if (!isAdmin && !product.getMerchantId().equals(requesterId)) {
            throw new ForbiddenException("Cannot delist another merchant's product");
        }

        if (product.getStatus() == ProductStatus.DELISTED) {
            throw new BadRequestException("Product is already delisted");
        }

        product.setStatus(ProductStatus.DELISTED);
        product.setDelistedAt(Instant.now());

        CatalogOutbox outbox = CatalogOutbox.builder()
                .eventType("product.delisted")
                .payload(Map.of(
                        "product_id",  productId,
                        "merchant_id", product.getMerchantId(),
                        "reason",      req.reason(),
                        "notes",       req.notes() != null ? req.notes() : "",
                        "timestamp",   Instant.now().toString()
                ))
                .aggregateId(productId)
                .build();

        productRepo.save(product);
        outboxRepo.save(outbox);
        redisService.evict(productId);

        log.warn("[traceId={}] Product delisted: productId={}, reason={}",
                span != null ? span.context().traceId() : "none", productId, req.reason());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 6 — POST /products/{id}/media
    // ─────────────────────────────────────────────────────────────────────────

    @Observed(name = "catalog.product.media", contextualName = "upload-media")
    @Transactional
    public MediaUploadResponse uploadMedia(String productId, MultipartFile file,
                                           String mediaType, int position, String merchantId) {
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("catalog.product_id",  productId);
            span.tag("catalog.merchant_id", merchantId);
            span.tag("catalog.media_type",  mediaType);
            span.tag("catalog.operation",   "upload-media");
        }

        findOwnedProduct(productId, merchantId);

        MediaUploadService.UploadResult upload = mediaUploadService.upload(productId, file, mediaType);

        ProductDetail detail = detailRepo.findByProductId(productId)
                .orElseThrow(() -> new NotFoundException("Product detail not found: " + productId));

        Map<String, Object> mediaEntry = new LinkedHashMap<>();
        mediaEntry.put("url",        upload.s3Url());
        mediaEntry.put("cdn_url",    upload.cdnUrl());
        mediaEntry.put("media_type", mediaType);
        mediaEntry.put("position",   position);
        detail.getMediaUrls().add(mediaEntry);
        detail.setUpdatedAt(Instant.now());
        detailRepo.save(detail);

        CatalogOutbox outbox = CatalogOutbox.builder()
                .eventType("product.updated")
                .payload(Map.of(
                        "product_id",  productId,
                        "change_type", "media_added",
                        "media_url",   upload.cdnUrl(),
                        "timestamp",   Instant.now().toString()
                ))
                .aggregateId(productId)
                .build();
        outboxRepo.save(outbox);

        redisService.evict(productId);

        return new MediaUploadResponse(upload.s3Url(), upload.cdnUrl(), mediaType, position);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 8 — GET /categories/{id}/products
    // ─────────────────────────────────────────────────────────────────────────

    @Observed(name = "catalog.category.browse", contextualName = "browse-category")
    public PagedProductResponse getProductsByCategory(String categoryId, int page, int size,
                                                      String sort, BigDecimal minPrice, BigDecimal maxPrice) {
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("catalog.category_id", categoryId);
            span.tag("catalog.page",        String.valueOf(page));
            span.tag("catalog.size",        String.valueOf(size));
            span.tag("catalog.operation",   "browse");
        }

        if (!categoryRepo.existsById(categoryId)) {
            throw new NotFoundException("Category not found: " + categoryId);
        }

        String cacheKey = String.format("cat:%s:p%d:s%d:%s", categoryId, page, size, sort);
        PagedProductResponse cached = redisService.getPage(cacheKey);
        if (cached != null) {
            if (span != null) span.tag("catalog.cache", "hit");
            return cached;
        }
        if (span != null) span.tag("catalog.cache", "miss");

        Pageable pageable = buildPageable(page, size, sort);

        Page<Product> productPage;
        if (minPrice != null || maxPrice != null) {
            BigDecimal min = minPrice != null ? minPrice : BigDecimal.ZERO;
            BigDecimal max = maxPrice != null ? maxPrice : new BigDecimal("999999999");
            productPage = productRepo.findByCategoryIdAndStatusAndPriceBetween(
                    categoryId, ProductStatus.ACTIVE, min, max, pageable);
        } else {
            productPage = productRepo.findByCategoryIdAndStatus(
                    categoryId, ProductStatus.ACTIVE, pageable);
        }

        List<String> productIds = productPage.getContent().stream().map(Product::getId).toList();
        Map<String, ProductDetail> detailMap = detailRepo.findByProductIdIn(productIds)
                .stream().collect(Collectors.toMap(ProductDetail::getProductId, d -> d));

        List<ProductSummaryDto> summaries = productPage.getContent().stream()
                .map(p -> toSummary(p, detailMap.get(p.getId())))
                .toList();

        PagedProductResponse response = new PagedProductResponse(
                summaries, page, size,
                productPage.getTotalElements(), productPage.getTotalPages());

        redisService.cachePage(cacheKey, response, 120);

        return response;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 9 — GET /merchants/{id}/products
    // ─────────────────────────────────────────────────────────────────────────

    public PagedProductResponse getMerchantProducts(String merchantId, String statusFilter,
                                                    int page, int size, String requesterId, boolean isAdmin) {
        if (!isAdmin && !merchantId.equals(requesterId)) {
            throw new ForbiddenException("Cannot view another merchant's products");
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Product> productPage;
        if (statusFilter != null) {
            ProductStatus status = ProductStatus.valueOf(statusFilter);
            productPage = productRepo.findByMerchantIdAndStatus(merchantId, status, pageable);
        } else {
            productPage = productRepo.findByMerchantId(merchantId, pageable);
        }

        List<String> productIds = productPage.getContent().stream().map(Product::getId).toList();
        Map<String, ProductDetail> detailMap = detailRepo.findByProductIdIn(productIds)
                .stream().collect(Collectors.toMap(ProductDetail::getProductId, d -> d));

        List<ProductSummaryDto> summaries = productPage.getContent().stream()
                .map(p -> toSummary(p, detailMap.get(p.getId())))
                .toList();

        return new PagedProductResponse(
                summaries, page, size,
                productPage.getTotalElements(), productPage.getTotalPages());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 10 / @Scheduled — Campaign tick
    // ─────────────────────────────────────────────────────────────────────────

    @Scheduled(fixedRate = 60_000)
    @SchedulerLock(name = "catalog-campaigns-tick", lockAtLeastFor = "PT50S", lockAtMostFor = "PT55S")
    @Transactional
    public void tickCampaigns() {
        Instant now = Instant.now();

        List<Product> toActivate = productRepo.findReadyToPromote(now);
        if (!toActivate.isEmpty()) {
            for (Product p : toActivate) {
                BigDecimal oldPrice = p.getPrice();
                p.setPrice(p.getPromotionalPrice());
                savePriceChangedOutbox(p.getId(), oldPrice, p.getPrice(), p.getCurrency(), "PROMOTIONAL_ACTIVATED");
                redisService.evict(p.getId());
            }
            productRepo.saveAll(toActivate);
            log.info("Campaigns activated: {} products → promotional pricing", toActivate.size());
        }

        List<Product> toRevert = productRepo.findExpiredPromos(now);
        if (!toRevert.isEmpty()) {
            for (Product p : toRevert) {
                BigDecimal oldPrice = p.getPrice();
                p.setPrice(p.getOriginalPrice());
                p.setPriceMode(PriceMode.FIXED);
                p.setPromotionalPrice(null);
                p.setOriginalPrice(null);
                p.setPromotionalStart(null);
                p.setPromotionalEnd(null);
                savePriceChangedOutbox(p.getId(), oldPrice, p.getPrice(), p.getCurrency(), "PROMOTIONAL_EXPIRED");
                redisService.evict(p.getId());
            }
            productRepo.saveAll(toRevert);
            log.info("Campaigns reverted: {} products → original pricing", toRevert.size());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Kafka consumer callbacks
    // ─────────────────────────────────────────────────────────────────────────

    public void handleMerchantSuspended(String merchantId) {
        log.warn("Merchant suspended — delisting all active products: merchantId={}", merchantId);
        List<Product> active = productRepo.findByMerchantIdAndStatusIn(
                merchantId, List.of(ProductStatus.ACTIVE, ProductStatus.DRAFT));

        for (Product p : active) {
            p.setStatus(ProductStatus.DELISTED);
            p.setDelistedAt(Instant.now());
            outboxRepo.save(CatalogOutbox.builder()
                    .eventType("product.delisted")
                    .payload(Map.of(
                            "product_id",  p.getId(),
                            "merchant_id", merchantId,
                            "reason",      "MERCHANT_SUSPENDED",
                            "timestamp",   Instant.now().toString()
                    ))
                    .aggregateId(p.getId())
                    .build());
            redisService.evict(p.getId());
        }
        productRepo.saveAll(active);
    }

    public void handleMerchantTerminated(String merchantId) {
        handleMerchantSuspended(merchantId);
        log.warn("Merchant terminated — all products delisted: merchantId={}", merchantId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Product findOwnedProduct(String productId, String merchantId) {
        Product product = productRepo.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product not found: " + productId));
        if (!product.getMerchantId().equals(merchantId)) {
            throw new ForbiddenException("Product not owned by merchant: " + merchantId);
        }
        return product;
    }

    private ProductDetailResponse assemble(Product p, ProductDetail detail) {
        PriceDetailDto priceDto = buildPriceDto(p);
        List<String>             categoryPath = List.of();
        List<Map<String, Object>> media       = detail != null ? detail.getMediaUrls() : List.of();
        Map<String, Object>       attributes  = detail != null ? detail.getAttributes() : Map.of();
        List<String>              tags        = detail != null ? detail.getTags()       : List.of();

        return new ProductDetailResponse(
                p.getId(), p.getTitle(),
                new MerchantSummaryDto(p.getMerchantId(), null, null),
                priceDto, p.getStatus().name(), true,
                categoryPath, attributes, media, tags,
                p.getCreatedAt(), p.getUpdatedAt());
    }

    private ProductSummaryDto toSummary(Product p, ProductDetail detail) {
        String thumb = null;
        if (detail != null && detail.getMediaUrls() != null && !detail.getMediaUrls().isEmpty()) {
            thumb = (String) detail.getMediaUrls().get(0).get("cdn_url");
        }
        return new ProductSummaryDto(
                p.getId(), p.getTitle(), buildPriceDto(p),
                p.getStatus().name(), thumb, p.getMerchantId(), p.getCreatedAt());
    }

    private PriceDetailDto buildPriceDto(Product p) {
        boolean onSale = p.getPriceMode() == PriceMode.PROMOTIONAL && p.getOriginalPrice() != null;
        Integer discountPct = null;
        if (onSale && p.getOriginalPrice().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal savings = p.getOriginalPrice().subtract(p.getPrice());
            discountPct = savings.multiply(BigDecimal.valueOf(100))
                    .divide(p.getOriginalPrice(), 0, RoundingMode.HALF_UP).intValue();
        }
        String display = p.getCurrency() + " $" + String.format("%,.2f", p.getPrice());
        return new PriceDetailDto(p.getPrice(), p.getCurrency(), p.getPriceMode().name(),
                display, onSale ? p.getOriginalPrice() : null, discountPct);
    }

    private void savePriceChangedOutbox(String productId, BigDecimal oldPrice, BigDecimal newPrice,
                                        String currency, String reason) {
        outboxRepo.save(CatalogOutbox.builder()
                .eventType("product.price.changed")
                .payload(Map.of(
                        "product_id", productId,
                        "old_price",  oldPrice.toString(),
                        "new_price",  newPrice.toString(),
                        "currency",   currency,
                        "reason",     reason,
                        "timestamp",  Instant.now().toString()
                ))
                .aggregateId(productId)
                .build());
    }

    private Pageable buildPageable(int page, int size, String sort) {
        Sort s = switch (sort != null ? sort : "newest") {
            case "price_asc"    -> Sort.by(Sort.Direction.ASC,  "price");
            case "price_desc"   -> Sort.by(Sort.Direction.DESC, "price");
            case "best_selling" -> Sort.by(Sort.Direction.DESC, "salesVolume");
            default             -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
        return PageRequest.of(page, size, s);
    }

    private Map<String, Object> buildProductPayload(String productId, CreateProductRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("product_id",  productId);
        m.put("merchant_id", req.merchantId());
        m.put("title",       req.title());
        m.put("category_id", req.categoryId());
        m.put("price",       req.price().amount().toString());
        m.put("currency",    req.price().currency());
        m.put("timestamp",   Instant.now().toString());
        return m;
    }

    private String buildSlug(String title) {
        if (title == null || title.isEmpty()) return "product";
        String slug = title.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-");
        return slug.length() > 100 ? slug.substring(0, 100) : slug;
    }

    private void validateFixed(UpdatePriceRequest req) {
        if (req.amount() == null || req.amount().compareTo(BigDecimal.ZERO) <= 0)
            throw new BadRequestException("Fixed price must be > 0");
    }

    private void validateTiered(UpdatePriceRequest req) {
        if (req.baseAmount() == null || req.baseAmount().compareTo(BigDecimal.ZERO) <= 0)
            throw new BadRequestException("Tiered base amount must be > 0");
        if (req.tiers() == null || req.tiers().isEmpty())
            throw new BadRequestException("Tiered pricing requires at least one tier");
    }

    private void validatePromotional(UpdatePriceRequest req) {
        if (req.promotionalPrice() == null || req.originalPrice() == null)
            throw new BadRequestException("Promotional pricing requires both prices");
        if (req.campaignStart() == null || req.campaignEnd() == null)
            throw new BadRequestException("Promotional pricing requires campaign dates");
        if (!req.campaignEnd().isAfter(req.campaignStart()))
            throw new BadRequestException("Campaign end must be after start");
        if (req.promotionalPrice().compareTo(req.originalPrice()) >= 0)
            throw new BadRequestException("Promotional price must be less than original price");
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}