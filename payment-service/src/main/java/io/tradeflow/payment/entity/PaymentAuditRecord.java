package io.tradeflow.payment.entity;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoEntityBase;
import lombok.*;
import org.bson.types.ObjectId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

// ─────────────────────────────────────────────────────────────────────────────
// PAYMENT AUDIT — MongoDB (reactive Panache)
//
// Stores complete raw Stripe API responses — schemaless, never mutated.
// Critical for dispute evidence: the original charge.succeeded payload
// from months ago becomes evidence submitted to Stripe's dispute API.
//
// One document per order — appended to on each Stripe event.
// ─────────────────────────────────────────────────────────────────────────────
@MongoEntity(collection = "payment_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentAuditRecord extends ReactivePanacheMongoEntityBase {

    public ObjectId id;

    public String orderId;
    public String stripeChargeId;
    public BigDecimal amount;
    public String currency;
    public String buyerId;
    public String merchantId;

    /** Complete raw Stripe charge response — never mutated */
    public Map<String, Object> rawStripeResponse;

    /** Webhook events appended as they arrive */
    @Builder.Default
    public List<Map<String, Object>> webhookEvents = new java.util.ArrayList<>();

    /** Refund records appended on refund */
    @Builder.Default
    public List<Map<String, Object>> refunds = new java.util.ArrayList<>();

    /** Dispute evidence appended when submitted */
    @Builder.Default
    public List<Map<String, Object>> disputeResponses = new java.util.ArrayList<>();

    public Instant recordedAt;
}
