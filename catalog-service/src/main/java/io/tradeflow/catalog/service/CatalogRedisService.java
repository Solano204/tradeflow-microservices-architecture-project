package io.tradeflow.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tradeflow.catalog.dto.CatalogDtos.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogRedisService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String PRODUCT_KEY_PREFIX = "product:";

    // ─────────────────────────────────────────────────────────────────────────
    // Product cache (hot products pre-warmed at startup with 1hr TTL)
    // ─────────────────────────────────────────────────────────────────────────

    public ProductDetailResponse getProduct(String productId) {
        return get(PRODUCT_KEY_PREFIX + productId, ProductDetailResponse.class);
    }

    public void cacheProduct(String productId, ProductDetailResponse product, long ttlSeconds) {
        set(PRODUCT_KEY_PREFIX + productId, product, ttlSeconds);
    }

    public void evict(String productId) {
        try {
            redisTemplate.delete(PRODUCT_KEY_PREFIX + productId);
            log.debug("Product cache evicted: {}", productId);
        } catch (Exception e) {
            log.warn("Redis eviction failed for productId={}: {}", productId, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Paginated browse cache (short TTL — browse pages go stale faster)
    // ─────────────────────────────────────────────────────────────────────────

    public PagedProductResponse getPage(String cacheKey) {
        return get(cacheKey, PagedProductResponse.class);
    }

    public void cachePage(String cacheKey, PagedProductResponse page, long ttlSeconds) {
        set(cacheKey, page, ttlSeconds);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Category tree cache (long TTL — almost never changes)
    // ─────────────────────────────────────────────────────────────────────────

    public CategoryTreeResponse getCategoryTree(String key) {
        return get(key, CategoryTreeResponse.class);
    }

    public void cacheCategoryTree(String key, CategoryTreeResponse tree, long ttlSeconds) {
        set(key, tree, ttlSeconds);
    }

    public void evictCategoryTree(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis eviction failed for key={}: {}", key, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Generic helpers — fail open (cache failures never break reads)
    // ─────────────────────────────────────────────────────────────────────────

    private <T> T get(String key, Class<T> type) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return null;
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("Redis read failed for key={}: {}", key, e.getMessage());
            return null; // fail open — fall through to DB
        }
    }

    private void set(String key, Object value, long ttlSeconds) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("Redis write failed for key={}: {}", key, e.getMessage());
        }
    }
}
