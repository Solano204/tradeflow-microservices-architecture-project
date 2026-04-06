package io.tradeflow.payment.repository;

import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoRepository;
import io.smallrye.mutiny.Uni;
import io.tradeflow.payment.entity.PaymentAuditRecord;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

@ApplicationScoped
public class PaymentAuditRepository implements ReactivePanacheMongoRepository<PaymentAuditRecord> {

    public Uni<PaymentAuditRecord> findByOrderId(String orderId) {
        return find("orderId", orderId).firstResult();
    }

    public Uni<PaymentAuditRecord> findByStripeChargeId(String chargeId) {
        return find("stripeChargeId", chargeId).firstResult();
    }

    public Uni<Void> appendWebhookEvent(String orderId, Map<String, Object> webhookEvent) {
        return findByOrderId(orderId)
                .onItem().ifNotNull().transformToUni(record -> {
                    record.webhookEvents.add(webhookEvent);
                    return record.update().map(__ -> null);
                })
                .onItem().ifNull().continueWith((Void) null).replaceWithVoid();
    }

    public Uni<Void> appendRefundRecord(String orderId, Map<String, Object> refundData) {
        return findByOrderId(orderId)
                .onItem().ifNotNull().transformToUni(record -> {
                    record.refunds.add(refundData);
                    return record.update().map(__ -> null);
                })
                .onItem().ifNull().continueWith((Void) null).replaceWithVoid();
    }

    public Uni<Void> appendDisputeResponse(String orderId, Map<String, Object> disputeData) {
        return findByOrderId(orderId)
                .onItem().ifNotNull().transformToUni(record -> {
                    record.disputeResponses.add(disputeData);
                    return record.update().map(__ -> null);
                })
                .onItem().ifNull().continueWith((Void) null).replaceWithVoid();
    }
}
