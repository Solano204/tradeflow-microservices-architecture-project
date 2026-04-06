package io.tradeflow.analytics.repository;

import io.tradeflow.analytics.entity.MerchantOrderSnapshot;
import io.tradeflow.analytics.entity.MerchantRevenueSnapshot;
import io.tradeflow.analytics.entity.SnapshotRecord;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface MerchantRevenueSnapshotRepository
        extends MongoRepository<MerchantRevenueSnapshot, String> {

    /**
     * Historical revenue for a merchant within a date range.
     * Used for 30d, 90d queries that exceed RocksDB retention.
     */
    @Query("{'merchant_id': ?0, 'date': {$gte: ?1, $lte: ?2}}")
    List<MerchantRevenueSnapshot> findByMerchantAndDateRange(
        String merchantId, LocalDate from, LocalDate to);

    /**
     * Platform-wide revenue for a date range (all merchants).
     */
    @Query("{'date': {$gte: ?0, $lte: ?1}}")
    List<MerchantRevenueSnapshot> findAllByDateRange(LocalDate from, LocalDate to);

    Optional<MerchantRevenueSnapshot> findByMerchantIdAndDate(String merchantId, LocalDate date);

    /**
     * Oldest available record for coverage reporting.
     */
    Optional<MerchantRevenueSnapshot> findFirstByOrderByDateAsc();

    /**
     * Total count of merchant-day records.
     */
    long count();
}
