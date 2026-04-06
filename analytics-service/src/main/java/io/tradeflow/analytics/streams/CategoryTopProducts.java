package io.tradeflow.analytics.streams;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Top-N products per category, bounded at 10.
 * Stored in: product-performance-store (keyed by categoryId)
 *
 * On every sale: the product's revenue is updated,
 * the list is re-sorted by revenue DESC, and trimmed to 10 entries.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CategoryTopProducts {

    private static final int MAX_TOP_N = 10;

    @Builder.Default
    private List<ProductRevenueSummary> topProducts = new ArrayList<>();

    public static CategoryTopProducts empty() {
        return new CategoryTopProducts(new ArrayList<>());
    }

    /**
     * Add or update a product's revenue contribution.
     * Re-sorts the list and trims to MAX_TOP_N.
     */
    public CategoryTopProducts addProduct(String productId, String merchantId,
                                           String title, String categoryId,
                                           double revenue) {
        List<ProductRevenueSummary> updated = new ArrayList<>(topProducts);

        // Find existing entry for this product and accumulate
        updated.stream()
            .filter(p -> p.getProductId().equals(productId))
            .findFirst()
            .ifPresentOrElse(
                existing -> {
                    updated.remove(existing);
                    updated.add(ProductRevenueSummary.builder()
                        .productId(productId)
                        .merchantId(merchantId)
                        .title(title)
                        .categoryId(categoryId)
                        .revenue(existing.getRevenue() + revenue)
                        .unitsSold(existing.getUnitsSold() + 1)
                        .build());
                },
                () -> updated.add(ProductRevenueSummary.builder()
                    .productId(productId)
                    .merchantId(merchantId)
                    .title(title)
                    .categoryId(categoryId)
                    .revenue(revenue)
                    .unitsSold(1)
                    .build())
            );

        // Sort DESC by revenue, keep top N
        updated.sort(Comparator.comparingDouble(ProductRevenueSummary::getRevenue).reversed());
        List<ProductRevenueSummary> top = updated.size() > MAX_TOP_N
            ? updated.subList(0, MAX_TOP_N)
            : updated;

        return new CategoryTopProducts(new ArrayList<>(top));
    }

    /**
     * Nested: a single product's revenue summary within a category.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProductRevenueSummary {
        private String productId;
        private String merchantId;
        private String title;
        private String categoryId;
        private double revenue;
        private int unitsSold;
    }
}
