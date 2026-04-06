package io.tradeflow.payment.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "dunning_cases",
        indexes = {
                @Index(name = "idx_dunning_merchant_id", columnList = "merchant_id"),
                @Index(name = "idx_dunning_stage_next_action", columnList = "current_stage, next_action_date")
        })
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DunningCase extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    public String id;

    @Column(name = "merchant_id", nullable = false, length = 36)
    public String merchantId;

    @Column(name = "failed_payment_id", nullable = false, length = 36)
    public String failedPaymentId;

    @Column(name = "payment_method_token", length = 200)
    public String paymentMethodToken;

    @Column(name = "amount_due", precision = 12, scale = 2)
    public BigDecimal amountDue;

    @Column(name = "currency", length = 3)
    @Builder.Default
    public String currency = "MXN";

    @Enumerated(EnumType.STRING)
    @Column(name = "current_stage", nullable = false, length = 20)
    @Builder.Default
    public DunningStage currentStage = DunningStage.GRACE_PERIOD;

    @Column(name = "retry_count")
    @Builder.Default
    public int retryCount = 0;

    @Column(name = "next_action_date")
    public Instant nextActionDate;

    @Column(name = "resolved_at")
    public Instant resolvedAt;

    @Column(name = "failure_started_at", nullable = false)
    public Instant failureStartedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at")
    public Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public long daysSinceFailure() {
        return java.time.Duration.between(failureStartedAt, Instant.now()).toDays();
    }

    public enum DunningStage {
        GRACE_PERIOD, RETRY_1, RETRY_2, SUSPENDED, TERMINATED, RESOLVED
    }
}