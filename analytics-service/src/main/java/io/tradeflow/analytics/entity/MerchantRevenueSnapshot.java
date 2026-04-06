package io.tradeflow.analytics.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Daily revenue snapshot — snapshotted from RocksDB at 3AM each night.
 * Serves long-range revenue queries (30d, 90d) beyond Kafka's 7-day retention.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "merchant_revenue_daily")
@CompoundIndexes({
        @CompoundIndex(name = "merchant_date_idx", def = "{'merchant_id': 1, 'date': 1}", unique = true),
        @CompoundIndex(name = "date_idx", def = "{'date': 1}")
})
public class MerchantRevenueSnapshot {

    @Id
    private String id;

    @Field("merchant_id")
    private String merchantId;

    private LocalDate date;

    @Field("window_start")
    private Instant windowStart;

    @Field("window_end")
    private Instant windowEnd;

    @Field("total_revenue")
    private double totalRevenue;

    @Field("transaction_count")
    private int transactionCount;

    @Field("avg_order_value")
    private double avgOrderValue;

    @Field("min_order_value")
    private double minOrderValue;

    @Field("max_order_value")
    private double maxOrderValue;

    private String currency;

    @Field("snapshot_version")
    @CreatedDate
    private Instant snapshotVersion;   // when this snapshot was written
}