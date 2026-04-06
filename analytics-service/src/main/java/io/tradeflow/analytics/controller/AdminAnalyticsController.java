package io.tradeflow.analytics.controller;

import io.tradeflow.analytics.dto.AnalyticsDtos.*;
import io.tradeflow.analytics.entity.SnapshotRecord;
import io.tradeflow.analytics.repository.MerchantRevenueSnapshotRepository;
import io.tradeflow.analytics.repository.SnapshotRecordRepository;
import io.tradeflow.analytics.scheduler.SnapshotScheduler;
import io.tradeflow.analytics.service.InteractiveQueriesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Admin endpoints for snapshot management.
 *
 * ENDPOINT 8: GET  /internal/analytics/snapshots/status
 * ENDPOINT 9: POST /internal/analytics/snapshots/trigger
 *
 * Restricted to internal traffic via API Gateway routing rules.
 */
@RestController
@RequestMapping("/internal/analytics")
@RequiredArgsConstructor
@Slf4j
public class AdminAnalyticsController {

    private final SnapshotScheduler snapshotScheduler;
    private final SnapshotRecordRepository snapshotRecordRepo;
    private final MerchantRevenueSnapshotRepository revenueSnapshotRepo;
    private final InteractiveQueriesService interactiveQueries;

    // ================================================================
    // ENDPOINT 8 — GET /internal/analytics/snapshots/status
    // ================================================================

    @GetMapping("/snapshots/status")
    @PreAuthorize("hasAuthority('SCOPE_internal') or hasAuthority('SCOPE_admin')")
    public ResponseEntity<SnapshotStatusResponse> getSnapshotStatus() {
        SnapshotInfo lastSnapshotInfo = snapshotRecordRepo
            .findFirstByOrderByCreatedAtDesc()
            .map(r -> SnapshotInfo.builder()
                .completedAt(r.getCompletedAt())
                .durationSeconds(r.getDurationSeconds())
                .recordsSnapshotted(r.getRecordsSnapshotted())
                .status(r.getStatus())
                .build())
            .orElse(null);

        long totalMerchantDays = revenueSnapshotRepo.count();
        Instant oldestRecord = revenueSnapshotRepo.findFirstByOrderByDateAsc()
            .map(s -> s.getWindowStart())
            .orElse(null);

        SnapshotStatusResponse response = SnapshotStatusResponse.builder()
            .lastSnapshot(lastSnapshotInfo)
            .mongodbCoverage(CoverageInfo.builder()
                .oldestRecord(oldestRecord)
                .totalMerchantDays(totalMerchantDays)
                .build())
            .rocksdbCoverage(RocksDbInfo.builder()
                .state(interactiveQueries.getStreamsState().name())
                .isQueryable(interactiveQueries.isReady())
                .build())
            .streamsState(interactiveQueries.getStreamsState().name())
            .build();

        return ResponseEntity.ok(response);
    }

    // ================================================================
    // ENDPOINT 9 — POST /internal/analytics/snapshots/trigger
    // ================================================================

    @PostMapping("/snapshots/trigger")
    @PreAuthorize("hasAuthority('SCOPE_internal') or hasAuthority('SCOPE_admin')")
    public ResponseEntity<SnapshotTriggerResponse> triggerSnapshot() {
        log.warn("Manual snapshot trigger by admin");
        Instant startedAt = Instant.now();

        SnapshotScheduler.SnapshotResult result = snapshotScheduler.triggerManually();

        long duration = java.time.Duration.between(startedAt, Instant.now()).getSeconds();
        String status = result.isSuccess() ? "SUCCESS" : "FAILED";

        SnapshotTriggerResponse response = SnapshotTriggerResponse.builder()
            .triggeredAt(startedAt)
            .recordsSnapshotted(result.totalRecords())
            .durationSeconds(duration)
            .status(status)
            .message(result.isSuccess()
                ? "Snapshot completed successfully: " + result.totalRecords() + " records"
                : "Snapshot failed: " + result.failureReason())
            .build();

        return ResponseEntity.ok(response);
    }
}
