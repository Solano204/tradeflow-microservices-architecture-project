package io.tradeflow.analytics.repository;

import io.tradeflow.analytics.entity.MerchantOrderSnapshot;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface MerchantOrderSnapshotRepository
        extends MongoRepository<MerchantOrderSnapshot, String> {

    @Query("{'merchant_id': ?0, 'date': {$gte: ?1, $lte: ?2}}")
    List<MerchantOrderSnapshot> findByMerchantAndDateRange(
        String merchantId, LocalDate from, LocalDate to);
}
