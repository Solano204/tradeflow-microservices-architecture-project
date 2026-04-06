package io.tradeflow.analytics.streams;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * The revenue aggregate maintained in RocksDB per merchant per time window.
 *
 * Updated on every payment.processed event that passes through Topology 1.
 * Each call to add() returns a new immutable aggregate (Kafka Streams style).
 *
 * Stored as: revenue-hourly-store, revenue-daily-store, revenue-weekly-store
 * Key: merchantId (String)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RevenueAggregate {

    private double totalRevenue;
    private int transactionCount;
    private double minTransaction;
    private double maxTransaction;
    private double avgTransaction;   // totalRevenue / transactionCount
    private String currency;
    private Instant windowStart;
    private Instant windowEnd;

    /**
     * Empty/initial aggregate for a new window.
     */
    public static RevenueAggregate empty() {
        return RevenueAggregate.builder()
            .totalRevenue(0.0)
            .transactionCount(0)
            .minTransaction(Double.MAX_VALUE)
            .maxTransaction(0.0)
            .avgTransaction(0.0)
            .currency("MXN")
            .build();
    }

    /**
     * Accumulate a new payment amount into this aggregate.
     * Returns a new aggregate — do not mutate.
     */
    public RevenueAggregate add(double amount) {
        int newCount = this.transactionCount + 1;
        double newTotal = this.totalRevenue + amount;
        return RevenueAggregate.builder()
            .totalRevenue(newTotal)
            .transactionCount(newCount)
            .minTransaction(this.transactionCount == 0 ? amount : Math.min(this.minTransaction, amount))
            .maxTransaction(Math.max(this.maxTransaction, amount))
            .avgTransaction(newTotal / newCount)
            .currency(this.currency != null ? this.currency : "MXN")
            .windowStart(this.windowStart)
            .windowEnd(this.windowEnd)
            .build();
    }

    /**
     * Merge two aggregates (used when combining multiple hourly windows into a total).
     */
    public static RevenueAggregate merge(RevenueAggregate a, RevenueAggregate b) {
        if (a == null) return b;
        if (b == null) return a;
        int newCount = a.transactionCount + b.transactionCount;
        double newTotal = a.totalRevenue + b.totalRevenue;
        return RevenueAggregate.builder()
            .totalRevenue(newTotal)
            .transactionCount(newCount)
            .minTransaction(Math.min(a.minTransaction, b.minTransaction))
            .maxTransaction(Math.max(a.maxTransaction, b.maxTransaction))
            .avgTransaction(newCount > 0 ? newTotal / newCount : 0.0)
            .currency(a.currency)
            .build();
    }

    public boolean isEmpty() {
        return transactionCount == 0;
    }
}
