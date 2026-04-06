package io.tradeflow.payment.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

// ─────────────────────────────────────────────────────────────────────────────
// DISPUTES — Chargeback tracking
// ─────────────────────────────────────────────────────────────────────────────
@Entity
@Table(name = "disputes",
        indexes = {
                @Index(name = "idx_disputes_order_id",    columnList = "order_id"),
                @Index(name = "idx_disputes_stripe_id",   columnList = "stripe_dispute_id", unique = true),
                @Index(name = "idx_disputes_status",      columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Dispute extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    public String id;

    @Column(name = "order_id", nullable = false, length = 36)
    public String orderId;

    @Column(name = "stripe_dispute_id", nullable = false, unique = true, length = 100)
    public String stripeDisputeId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    public BigDecimal amount;

    @Column(name = "reason", length = 100)
    public String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    public DisputeStatus status = DisputeStatus.OPEN;

    @Column(name = "evidence_due_date")
    public Instant evidenceDueDate;

    @Column(name = "submitted_at")
    public Instant submittedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public enum DisputeStatus { OPEN, EVIDENCE_SUBMITTED, UNDER_REVIEW, WON, LOST }
}
