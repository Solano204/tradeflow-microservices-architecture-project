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
 * Daily order volume snapshot per merchant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "merchant_orders_daily")
@CompoundIndexes({
        @CompoundIndex(name = "merchant_date_idx", def = "{'merchant_id': 1, 'date': 1}", unique = true)
})
public class MerchantOrderSnapshot {

    @Id
    private String id;

    @Field("merchant_id")
    private String merchantId;

    private LocalDate date;

    @Field("total_created")
    private long totalCreated;

    @Field("total_cancelled")
    private long totalCancelled;

    @Field("cancellation_rate")
    private double cancellationRate;

    @Field("snapshot_version")
    @CreatedDate
    private Instant snapshotVersion;
}