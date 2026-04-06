package io.tradeflow.analytics.service;

import io.tradeflow.analytics.entity.MerchantRevenueSnapshot;
import io.tradeflow.analytics.entity.SnapshotRecord;
import io.tradeflow.analytics.repository.MerchantOrderSnapshotRepository;
import io.tradeflow.analytics.repository.MerchantRevenueSnapshotRepository;
import io.tradeflow.analytics.repository.SnapshotRecordRepository;
import io.tradeflow.analytics.streams.RevenueAggregate;
import io.tradeflow.analytics.streams.RevenueStreamTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SnapshotService {

    private final StreamsBuilderFactoryBean streamsBuilderFactoryBean;
    private final MerchantRevenueSnapshotRepository revenueRepo;
    private final MerchantOrderSnapshotRepository orderRepo;
    private final SnapshotRecordRepository snapshotRecordRepo;

    @Value("${analytics.snapshot.batch-size:500}")
    private int batchSize;

    @Scheduled(cron = "${analytics.snapshot.cron:0 0 3 * * ?}")
    @SchedulerLock(name = "analyticsSnapshot", lockAtLeastFor = "PT1H", lockAtMostFor = "PT4H")
    public void snapshotStateStoresToMongoDB() {
        runSnapshot("SCHEDULED");
    }

    public SnapshotResult triggerManually() {
        return runSnapshot("ADMIN_MANUAL");
    }

    private SnapshotResult runSnapshot(String triggeredBy) {
        log.info("Starting analytics snapshot (triggeredBy={})", triggeredBy);
        Instant startedAt = Instant.now();

        int revenueRecords = 0;
        int orderRecords = 0;

        try {
            KafkaStreams streams = streamsBuilderFactoryBean.getKafkaStreams();
            if (streams == null || streams.state() != KafkaStreams.State.RUNNING) {
                String reason = "Kafka Streams not running";
                log.warn("Snapshot skipped: {}", reason);
                snapshotRecordRepo.save(SnapshotRecord.failure(reason));
                return new SnapshotResult(0, 0, reason);
            }

            revenueRecords = snapshotRevenueStore(streams);
            log.info("Revenue snapshot: {} records", revenueRecords);

            long durationSeconds = java.time.Duration.between(startedAt, Instant.now()).getSeconds();
            int totalRecords = revenueRecords + orderRecords;

            SnapshotRecord record = SnapshotRecord.success(durationSeconds, totalRecords);
            record.setStartedAt(startedAt);
            record.setTriggeredBy(triggeredBy);
            snapshotRecordRepo.save(record);

            log.info("Snapshot complete: revenueRecords={}, orderRecords={}, duration={}s",
                    revenueRecords, orderRecords, durationSeconds);

            return new SnapshotResult(revenueRecords, orderRecords, null);

        } catch (Exception e) {
            log.error("Snapshot failed: {}", e.getMessage(), e);
            snapshotRecordRepo.save(SnapshotRecord.failure(e.getMessage()));
            return new SnapshotResult(revenueRecords, orderRecords, e.getMessage());
        }
    }

    private int snapshotRevenueStore(KafkaStreams streams) {
        ReadOnlyWindowStore<String, RevenueAggregate> store;
        try {
            // Fixed: Use STORE_DAILY instead of DAILY_STORE
            store = streams.store(StoreQueryParameters.fromNameAndType(
                    RevenueStreamTopology.STORE_DAILY, QueryableStoreTypes.windowStore()));
        } catch (Exception e) {
            log.error("Could not open revenue store for snapshot: {}", e.getMessage());
            return 0;
        }

        List<MerchantRevenueSnapshot> batch = new ArrayList<>();
        int count = 0;

        try (KeyValueIterator<Windowed<String>, RevenueAggregate> iter = store.all()) {
            while (iter.hasNext()) {
                KeyValue<Windowed<String>, RevenueAggregate> entry = iter.next();
                if (entry.value == null) continue;

                String merchantId = entry.key.key();
                Instant windowStart = entry.key.window().startTime();
                // Fixed: Convert Instant to LocalDate properly
                LocalDate date = windowStart.atZone(ZoneOffset.UTC).toLocalDate();

                MerchantRevenueSnapshot snapshot = MerchantRevenueSnapshot.builder()
                        .merchantId(merchantId)
                        .date(date)
                        .windowStart(windowStart)
                        .windowEnd(entry.key.window().endTime())
                        .totalRevenue(entry.value.getTotalRevenue())
                        .transactionCount(entry.value.getTransactionCount())
                        .avgOrderValue(entry.value.getAvgTransaction())
                        .minOrderValue(entry.value.getMinTransaction() == Double.MAX_VALUE
                                ? 0 : entry.value.getMinTransaction())
                        .maxOrderValue(entry.value.getMaxTransaction())
                        .currency("MXN")
                        .snapshotVersion(Instant.now())
                        .build();

                batch.add(snapshot);
                count++;

                if (batch.size() >= batchSize) {
                    bulkUpsertRevenue(batch);
                    batch.clear();
                }
            }

            if (!batch.isEmpty()) {
                bulkUpsertRevenue(batch);
            }
        }

        return count;
    }

    private void bulkUpsertRevenue(List<MerchantRevenueSnapshot> snapshots) {
        for (MerchantRevenueSnapshot snapshot : snapshots) {
            revenueRepo.findByMerchantIdAndDate(snapshot.getMerchantId(), snapshot.getDate())
                    .ifPresentOrElse(
                            existing -> {
                                existing.setTotalRevenue(snapshot.getTotalRevenue());
                                existing.setTransactionCount(snapshot.getTransactionCount());
                                existing.setAvgOrderValue(snapshot.getAvgOrderValue());
                                existing.setSnapshotVersion(Instant.now());
                                revenueRepo.save(existing);
                            },
                            () -> revenueRepo.save(snapshot)
                    );
        }
    }

    public record SnapshotResult(
            int revenueRecords,
            int orderRecords,
            String failureReason
    ) {
        public boolean isSuccess() { return failureReason == null; }
        public int totalRecords() { return revenueRecords + orderRecords; }
    }
}