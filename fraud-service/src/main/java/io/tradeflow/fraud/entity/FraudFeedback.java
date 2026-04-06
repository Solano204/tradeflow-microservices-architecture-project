package io.tradeflow.fraud.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.math.BigDecimal;
import java.time.Instant;

// ─────────────────────────────────────────────────────────────────────────────
// FRAUD FEEDBACK — MongoDB
//
// Labeled training samples from confirmed disputes/chargebacks.
// Inserted when fraud.confirmed Kafka event arrives or admin POST /fraud/feedback.
//
// Weekly Spring Batch job reads WHERE used_in_model_version IS NULL,
// promotes confirmed FRAUD vectors into the fraud cluster,
// then marks used_in_model_version = "v2024-W47".
//
// This is the feedback loop that makes the model improve without redeployment.
// ─────────────────────────────────────────────────────────────────────────────
@Document(collection = "fraud_feedback")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FraudFeedback {

    @Id
    private String id;

    @Indexed
    @Field("order_id")
    private String orderId;

    @Indexed
    @Field("buyer_id")
    private String buyerId;

    @Field("original_score")
    private Double originalScore;

    @Field("original_decision")
    private String originalDecision;

    @Field("confirmed_fraud_type")
    private String confirmedFraudType;

    @Field("confirmed_by")
    private String confirmedBy;

    @Field("source")
    private String source;

    /** True if the model approved this and it later turned out to be fraud */
    @Field("was_false_negative")
    @Builder.Default
    private boolean wasFalseNegative = false;

    @Field("amount")
    private BigDecimal amount;

    @Field("notes")
    private String notes;

    /** Null until the weekly batch job uses this sample for training */
    @Field("used_in_model_version")
    private String usedInModelVersion;

    @Field("confirmed_at")
    private Instant confirmedAt;

    @Field("created_at")
    private Instant createdAt;
}
