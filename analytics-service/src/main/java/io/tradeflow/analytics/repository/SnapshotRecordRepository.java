package io.tradeflow.analytics.repository;

import io.tradeflow.analytics.entity.SnapshotRecord;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SnapshotRecordRepository extends MongoRepository<SnapshotRecord, String> {

    Optional<SnapshotRecord> findFirstByOrderByCreatedAtDesc();

    Optional<SnapshotRecord> findFirstByStatusOrderByCreatedAtDesc(String status);
}
