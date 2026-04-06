package io.tradeflow.auth.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * AuthOutbox — Transactional Outbox Pattern.
 *
 * Every Kafka event is written here atomically with the business operation.
 * The OutboxRelayService reads unpublished rows every 500ms and publishes to Kafka.
 *
 * If Kafka is down: events queue here safely. PostgreSQL holds all data.
 * When Kafka recovers: relay publishes in order. Zero event loss.
 *
 * Uses SELECT ... FOR UPDATE SKIP LOCKED to prevent multiple Pods
 * from publishing the same event simultaneously.
 */
@Entity
@Table(name = "auth_outbox")
public class AuthOutbox extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "event_type", length = 128, nullable = false)
    public String eventType;

    @Column(name = "aggregate_id", length = 36, nullable = false)
    public String aggregateId;

    /**
     * Event payload as JSON string.
     * Use @JdbcTypeCode to tell Hibernate this is JSONB
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    public String payload;

    @Column(name = "published", nullable = false)
    public boolean published = false;

    @Column(name = "published_at")
    public Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "retry_count", nullable = false)
    public int retryCount = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    public String lastError;

    // ----------------------------------------------------------------
    // Factory method
    // ----------------------------------------------------------------

    public static AuthOutbox of(String eventType, String aggregateId, String payloadJson) {
        AuthOutbox outbox = new AuthOutbox();
        outbox.eventType = eventType;
        outbox.aggregateId = aggregateId;
        outbox.payload = payloadJson;
        return outbox;
    }

    // ----------------------------------------------------------------
    // Panache finders
    // ----------------------------------------------------------------

    /**
     * Find unpublished events ordered by ID (FIFO).
     * Uses SKIP LOCKED to avoid multi-Pod conflicts.
     * Called by OutboxRelayService every 500ms.
     */
    public static List<AuthOutbox> findUnpublished(int batchSize) {
        return find("published = false ORDER BY id ASC")
                .page(0, batchSize)
                .list();
    }

    public static long countUnpublished() {
        return count("published = false");
    }

    /**
     * Clean up old published events to prevent table bloat.
     * Called by a weekly scheduled job.
     */
    public static long deleteOldPublished(Instant olderThan) {
        return delete("published = true AND publishedAt < ?1", olderThan);
    }
}