package io.tradeflow.fraud.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

// ─────────────────────────────────────────────────────────────────────────────
// FRAUD VECTOR — MongoDB
//
// The core ML store. Each document holds:
//   - embedding: float[1536] — the transaction's position in vector space
//   - label: FRAUD | LEGITIMATE | REVIEW — ground truth (provisional → confirmed)
//   - features_snapshot: human-readable context at scoring time (for explainability)
//
// MongoDB Atlas Vector Search runs $vectorSearch against this collection:
//   filter: { label: "FRAUD" }
//   queryVector: new transaction's embedding
//   → returns top-20 nearest fraud neighbors with cosine similarity scores
//
// Index created in Atlas UI or via Atlas CLI:
//   { "mappings": { "dynamic": false, "fields": {
//     "embedding": { "type": "knnVector", "dimensions": 1536, "similarity": "cosine" }
//   }}}
// ─────────────────────────────────────────────────────────────────────────────
@Document(collection = "fraud_vectors")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FraudVector {

    @Id
    private String id;

    @Indexed
    @Field("order_id")
    private String orderId;

    @Indexed
    @Field("buyer_id")
    private String buyerId;

    /** 1536-dimensional float array — the transaction's vector in embedding space */
    @Field("embedding")
    private float[] embedding;

    /** FRAUD | LEGITIMATE | REVIEW — provisional at score time, updated on feedback */
    @Field("label")
    @Builder.Default
    private String label = "LEGITIMATE";

    /** CARD_STOLEN | ACCOUNT_TAKEOVER | PAYMENT_FRAUD | null */
    @Field("fraud_type")
    private String fraudType;

    @Field("amount")
    private BigDecimal amount;

    @Field("currency")
    private String currency;

    /** Human-readable features at scoring time — for explainability endpoint */
    @Field("features_snapshot")
    private Map<String, Object> featuresSnapshot;

    @Field("score")
    private double score;

    @Field("decision")
    private String decision;

    @Field("model_version")
    private String modelVersion;

    @Field("scored_at")
    private Instant scoredAt;

    /** Set when feedback confirms this vector's label */
    @Field("confirmed_at")
    private Instant confirmedAt;

    /** Nearest fraud neighbors found during scoring — stored for explainability */
    @Field("nearest_neighbors")
    private List<Map<String, Object>> nearestNeighbors;
}
