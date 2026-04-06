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
 * Order volume aggregate: created vs cancelled counts per merchant per hour.
 * Stored in: orders-created-hourly-store, orders-cancelled-hourly-store
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderVolumeAggregate {

    private long totalCreated;
    private long totalCancelled;
    private double cancellationRate;   // totalCancelled / totalCreated

    public static OrderVolumeAggregate of(long created, long cancelled) {
        double rate = created > 0 ? (double) cancelled / created : 0.0;
        return new OrderVolumeAggregate(created, cancelled, rate);
    }

    public static OrderVolumeAggregate empty() {
        return new OrderVolumeAggregate(0L, 0L, 0.0);
    }
}
