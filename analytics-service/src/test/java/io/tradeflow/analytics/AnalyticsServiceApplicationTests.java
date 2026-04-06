package io.tradeflow.analytics;

import io.tradeflow.analytics.streams.RevenueAggregate;
import io.tradeflow.analytics.streams.CategoryTopProducts;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for aggregate logic — no Spring context needed.
 * Integration tests would require an embedded Kafka cluster (via spring-kafka-test).
 */
class AnalyticsServiceApplicationTests {

    @Test
    void revenueAggregateAccumulatesCorrectly() {
        RevenueAggregate agg = RevenueAggregate.empty();
        agg = agg.add(1000.0);
        agg = agg.add(2000.0);
        agg = agg.add(500.0);

        assertThat(agg.getTotalRevenue()).isEqualTo(3500.0);
        assertThat(agg.getTransactionCount()).isEqualTo(3);
        assertThat(agg.getAvgTransaction()).isEqualTo(3500.0 / 3);
        assertThat(agg.getMinTransaction()).isEqualTo(500.0);
        assertThat(agg.getMaxTransaction()).isEqualTo(2000.0);
    }

    @Test
    void revenueAggregateMergeIsCommutative() {
        RevenueAggregate a = RevenueAggregate.empty().add(1000.0).add(2000.0);
        RevenueAggregate b = RevenueAggregate.empty().add(500.0);

        RevenueAggregate merged = RevenueAggregate.merge(a, b);
        assertThat(merged.getTotalRevenue()).isEqualTo(3500.0);
        assertThat(merged.getTransactionCount()).isEqualTo(3);
    }

    @Test
    void categoryTopProductsBoundedAtTen() {
        CategoryTopProducts top = CategoryTopProducts.empty();

        // Add 12 products
        for (int i = 1; i <= 12; i++) {
            top = top.addProduct(
                "prod_" + i, "merchant_1", "Product " + i, "cat_electronics",
                i * 1000.0   // Higher index = higher revenue
            );
        }

        // Should only keep top 10
        assertThat(top.getTopProducts()).hasSize(10);
        // Highest revenue products should be ranked first
        assertThat(top.getTopProducts().get(0).getRevenue()).isEqualTo(12000.0);
        assertThat(top.getTopProducts().get(9).getRevenue()).isEqualTo(3000.0);
    }

    @Test
    void categoryTopProductsUpdatesExistingProduct() {
        CategoryTopProducts top = CategoryTopProducts.empty();
        top = top.addProduct("prod_1", "merchant_1", "Product 1", "cat_electronics", 1000.0);
        top = top.addProduct("prod_1", "merchant_1", "Product 1", "cat_electronics", 2000.0);

        // Should accumulate revenue, not add duplicate
        assertThat(top.getTopProducts()).hasSize(1);
        assertThat(top.getTopProducts().get(0).getRevenue()).isEqualTo(3000.0);
        assertThat(top.getTopProducts().get(0).getUnitsSold()).isEqualTo(2);
    }

    @Test
    void revenueAggregateEmptyReturnsZeroValues() {
        RevenueAggregate empty = RevenueAggregate.empty();
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.getTotalRevenue()).isEqualTo(0.0);
        assertThat(empty.getTransactionCount()).isEqualTo(0);
    }
}
