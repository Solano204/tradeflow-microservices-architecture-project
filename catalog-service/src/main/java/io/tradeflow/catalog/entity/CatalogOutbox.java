package io.tradeflow.catalog.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

// ─────────────────────────────────────────────────────────────────────────────
// CATEGORY (Hierarchical — category tree cached in Redis)
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// PRICING RULES (tiers and promotional windows — child of Product)
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// CATALOG OUTBOX (Transactional outbox pattern)
// ─────────────────────────────────────────────────────────────────────────────

@Entity
@Table(name = "catalog_outbox",
        indexes = {
                @Index(name = "idx_catalog_outbox_unpublished",
                        columnList = "published, created_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CatalogOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "aggregate_id", length = 36)
    private String aggregateId;

    @Column(name = "published", nullable = false)
    @Builder.Default
    private boolean published = false;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() { createdAt = Instant.now(); }
}
