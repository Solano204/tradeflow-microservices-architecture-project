package io.tradeflow.fraud.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

// ─────────────────────────────────────────────────────────────────────────────
// BUYER BASELINE — MongoDB
//
// Behavioral profile built up over time. Used for:
//   1. Amount deviation score (actual / avg_90d)
//   2. Velocity scoring (recent purchases vs. baseline)
//   3. Device/IP anomaly detection
//   4. Circuit Breaker fallback (cached in Redis, TTL 1h)
//   5. Cold-start detection (transaction_count < 3)
//
// risk_tier: TRUSTED | STANDARD | ELEVATED | HIGH_RISK
//   TRUSTED  → fast-path rule (ALLOW if ML score < 0.5)
//   HIGH_RISK → elevated scrutiny, manual review threshold lower
// ─────────────────────────────────────────────────────────────────────────────
@Document(collection = "buyer_baselines")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BuyerBaseline {

    @Id
    private String id;

    @Indexed(unique = true)
    @Field("buyer_id")
    private String buyerId;

    @Field("transaction_count")
    @Builder.Default
    private int transactionCount = 0;

    @Field("avg_order_value_90d")
    @Builder.Default
    private BigDecimal avgOrderValue90d = BigDecimal.ZERO;

    @Field("max_order_value_90d")
    @Builder.Default
    private BigDecimal maxOrderValue90d = BigDecimal.ZERO;

    @Field("purchase_velocity_7d")
    @Builder.Default
    private int purchaseVelocity7d = 0;

    @Field("usual_device_fingerprints")
    @Builder.Default
    private List<String> usualDeviceFingerprints = new java.util.ArrayList<>();

    @Field("device_history")
    @Builder.Default
    private List<Map<String, Object>> deviceHistory = new java.util.ArrayList<>();

    @Field("usual_ip_countries")
    @Builder.Default
    private List<String> usualIpCountries = new java.util.ArrayList<>();

    @Field("usual_order_hours")
    @Builder.Default
    private List<Integer> usualOrderHours = new java.util.ArrayList<>();

    @Field("usual_categories")
    @Builder.Default
    private List<String> usualCategories = new java.util.ArrayList<>();

    @Field("last_device_change")
    private Instant lastDeviceChange;

    @Field("last_transaction_at")
    private Instant lastTransactionAt;

    @Field("last_score")
    private Double lastScore;

    @Field("last_score_at")
    private Instant lastScoreAt;

    @Field("flags")
    @Builder.Default
    private List<String> flags = new java.util.ArrayList<>();

    /** TRUSTED | STANDARD | ELEVATED | HIGH_RISK */
    @Field("risk_tier")
    @Builder.Default
    private String riskTier = "STANDARD";

    @Field("first_transaction_at")
    private Instant firstTransactionAt;

    @Field("last_updated")
    private Instant lastUpdated;
}

