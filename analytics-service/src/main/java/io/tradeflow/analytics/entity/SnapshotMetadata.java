package io.tradeflow.analytics.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * Daily order volume snapshot per merchant.
 */

// ====================================================================

/**
 * Metadata record for each snapshot run.
 * Read by GET /internal/analytics/snapshots/status
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "snapshot_metadata")
public class SnapshotMetadata {

    public enum SnapshotStatus { SUCCESS, FAILED, IN_PROGRESS }

    @Id private String id;

    @Field("started_at")    private Instant startedAt;
    @Field("completed_at")  private Instant completedAt;
    @Field("duration_seconds") private long durationSeconds;
    @Field("records_snapshotted") private int recordsSnapshotted;
    private SnapshotStatus status;
    @Field("error_message") private String errorMessage;

    @CreatedDate
    @Field("created_at") private Instant createdAt;

    public static SnapshotMetadata success(long durationSeconds, int records) {
        return SnapshotMetadata.builder()
            .startedAt(Instant.now().minusSeconds(durationSeconds))
            .completedAt(Instant.now())
            .durationSeconds(durationSeconds)
            .recordsSnapshotted(records)
            .status(SnapshotStatus.SUCCESS)
            .build();
    }

    public static SnapshotMetadata failed(String errorMessage) {
        return SnapshotMetadata.builder()
            .completedAt(Instant.now())
            .status(SnapshotStatus.FAILED)
            .errorMessage(errorMessage)
            .build();
    }
}
