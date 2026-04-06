package io.tradeflow.catalog.service;

import io.tradeflow.catalog.dto.CatalogDtos.*;
import io.tradeflow.catalog.entity.Category;
import io.tradeflow.catalog.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Category Service — ENDPOINT 7: GET /categories
 *
 * The category tree is "immutable-ish" — categories almost never change.
 * Cache strategy: full tree cached in Redis, TTL = 1 HOUR.
 * On admin category change: DEL "category:tree" forces rebuild on next request.
 *
 * The tree also carries required_attributes per leaf category:
 *   Electronics > Phones: requires [brand, os, storage_gb, connector_type]
 *   This drives the dynamic product creation form in the merchant UI.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CategoryService {

    private final CategoryRepository categoryRepo;
    private final CatalogRedisService redisService;

    private static final String CATEGORY_TREE_KEY = "category:tree";

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 7 — GET /categories
    // Redis (TTL 1hr) → PostgreSQL tree build → cache
    // ─────────────────────────────────────────────────────────────────────────

    public CategoryTreeResponse getCategoryTree() {
        // 99.9% of requests: Redis hit (< 1ms)
        CategoryTreeResponse cached = redisService.getCategoryTree(CATEGORY_TREE_KEY);
        if (cached != null) return cached;

        // Cache miss (cold start or admin category update)
        List<Category> allCategories = categoryRepo.findByActiveTrue();
        List<CategoryDto> roots = buildTree(allCategories, null);
        CategoryTreeResponse response = new CategoryTreeResponse(roots);

        // Cache for 1 hour — categories rarely change
        redisService.cacheCategoryTree(CATEGORY_TREE_KEY, response, 3600);

        log.debug("Category tree rebuilt: {} categories", allCategories.size());
        return response;
    }

    /**
     * Called by admin operations (add/modify category) to force a tree rebuild.
     * DEL the Redis key → next request rebuilds from PostgreSQL.
     */
    public void invalidateCategoryTree() {
        redisService.evictCategoryTree(CATEGORY_TREE_KEY);
        log.info("Category tree cache invalidated");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private — recursive tree builder
    // ─────────────────────────────────────────────────────────────────────────

    private List<CategoryDto> buildTree(List<Category> all, String parentId) {
        return all.stream()
                .filter(c -> Objects.equals(c.getParentId(), parentId))
                .sorted(Comparator.comparingInt(Category::getSortOrder))
                .map(c -> new CategoryDto(
                        c.getId(),
                        c.getName(),
                        c.getSlug(),
                        c.getRequiredAttributes() != null ? c.getRequiredAttributes() : List.of(),
                        buildTree(all, c.getId())  // recursive
                ))
                .collect(Collectors.toList());
    }
}
