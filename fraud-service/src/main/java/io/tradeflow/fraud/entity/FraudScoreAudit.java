package io.tradeflow.fraud.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

// ─────────────────────────────────────────────────────────────────────────────
// FRAUD SCORES AUDIT — PostgreSQL (append-only, immutable)
//
// Every scoring decision is recorded here for compliance explainability.
// GDPR Article 22 / LFPDPPP require automated decision explanations.
// Separate from MongoDB fraud_vectors — this is the compliance record.
// ─────────────────────────────────────────────────────────────────────────────
@jakarta.persistence.Entity
@Table(name = "fraud_scores_audit",
        indexes = {
                @Index(name = "idx_fraud_audit_order_id",  columnList = "order_id", unique = true),
                @Index(name = "idx_fraud_audit_buyer_id",  columnList = "buyer_id"),
                @Index(name = "idx_fraud_audit_decision",  columnList = "decision, scored_at"),
                @Index(name = "idx_fraud_audit_scored_at", columnList = "scored_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FraudScoreAudit {

    @jakarta.persistence.Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "order_id", nullable = false, unique = true, length = 36)
    private String orderId;

    @Column(name = "buyer_id", nullable = false, length = 36)
    private String buyerId;

    @Column(name = "merchant_id", length = 36)
    private String merchantId;

    @Column(name = "score", nullable = false)
    private double score;

    @Column(name = "decision", nullable = false, length = 20)
    private String decision;

    @Column(name = "confidence")
    private double confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "score_components", columnDefinition = "jsonb")
    private Map<String, Object> scoreComponents;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rules_evaluated", columnDefinition = "jsonb")
    private Map<String, Object> rulesEvaluated;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "buyer_context_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> buyerContextSnapshot;

    @Column(name = "model_version", length = 30)
    private String modelVersion;

    @Column(name = "processing_time_ms")
    private long processingTimeMs;

    @Column(name = "scored_at", nullable = false, updatable = false)
    private Instant scoredAt;

    @PrePersist
    void onCreate() { scoredAt = Instant.now(); }
}
