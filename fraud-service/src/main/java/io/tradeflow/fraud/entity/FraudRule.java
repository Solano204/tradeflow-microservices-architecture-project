package io.tradeflow.fraud.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.Map;

// ─────────────────────────────────────────────────────────────────────────────
// FRAUD RULE — MongoDB
//
// Live rule engine. Rules are loaded into in-memory volatile cache every 60s.
// Can be added/modified/deactivated without redeploying — compliance response
// to emerging attack patterns in minutes.
//
// Rule types:
//   BLOCK  → override final score to 1.0 (hard block, skip ML)
//   ALLOW  → override final score to 0.0 if ML score < 0.5 (trusted buyer fast-path)
//   REVIEW → floor score at 0.65 (force manual review)
//
// Priority: lower = higher priority (1 fires before 10)
// ─────────────────────────────────────────────────────────────────────────────
@Document(collection = "fraud_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FraudRule {

    @Id
    private String id;

    @Indexed(unique = true)
    @Field("rule_name")
    private String ruleName;

    /** BLOCK | ALLOW | REVIEW */
    @Field("rule_type")
    private String ruleType;

    @Field("description")
    private String description;

    /** The condition DSL document */
    @Field("condition")
    private Map<String, Object> condition;

    /** Lower = higher priority. BLOCK rules typically priority 1-5, ALLOW 10+ */
    @Field("priority")
    @Builder.Default
    private int priority = 50;

    @Field("active")
    @Builder.Default
    private boolean active = true;

    @Field("created_by")
    private String createdBy;

    @Field("updated_by")
    private String updatedBy;

    @Field("justification")
    private String justification;

    @Field("change_reason")
    private String changeReason;

    @Field("expires_at")
    private Instant expiresAt;

    /** How many times this rule has fired (incremented async) */
    @Field("trigger_count")
    @Builder.Default
    private long triggerCount = 0;

    @Field("trigger_count_7d")
    @Builder.Default
    private long triggerCount7d = 0;

    /** FP rate: blocked orders that were actually legitimate */
    @Field("false_positive_rate")
    @Builder.Default
    private double falsePositiveRate = 0.0;

    @Field("created_at")
    private Instant createdAt;

    @Field("updated_at")
    private Instant updatedAt;

    @org.springframework.data.annotation.Transient
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
