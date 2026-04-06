package io.tradeflow.analytics.repository;

import io.tradeflow.analytics.entity.SnapshotMetadata;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SnapshotMetadataRepository extends MongoRepository<SnapshotMetadata, String> {

    Optional<SnapshotMetadata> findTopByOrderByCreatedAtDesc();

    Optional<SnapshotMetadata> findTopByStatusOrderByCreatedAtDesc(SnapshotMetadata.SnapshotStatus status);
}
