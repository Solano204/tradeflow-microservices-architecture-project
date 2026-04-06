package io.tradeflow.analytics.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * Audit record for each snapshot job run.
 * Used by GET /internal/analytics/snapshots/status
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "snapshot_metadata")
public class SnapshotRecord {

    @Id
    private String id;

    private String status;      // SUCCESS | FAILED | IN_PROGRESS

    @Field("started_at")
    private Instant startedAt;

    @Field("completed_at")
    private Instant completedAt;

    @Field("duration_seconds")
    private long durationSeconds;

    @Field("records_snapshotted")
    private int recordsSnapshotted;

    @Field("failure_reason")
    private String failureReason;

    @Field("triggered_by")
    private String triggeredBy;   // SCHEDULED | ADMIN_MANUAL

    @CreatedDate
    @Field("created_at")
    private Instant createdAt;

    public static SnapshotRecord success(long durationSeconds, int records) {
        return SnapshotRecord.builder()
            .status("SUCCESS")
            .durationSeconds(durationSeconds)
            .recordsSnapshotted(records)
            .completedAt(Instant.now())
            .triggeredBy("SCHEDULED")
            .build();
    }

    public static SnapshotRecord failure(String reason) {
        return SnapshotRecord.builder()
            .status("FAILED")
            .failureReason(reason)
            .completedAt(Instant.now())
            .build();
    }
}
