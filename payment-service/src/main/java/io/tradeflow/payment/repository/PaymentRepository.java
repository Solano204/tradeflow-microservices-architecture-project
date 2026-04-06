package io.tradeflow.payment.repository;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import io.tradeflow.payment.entity.Payment;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;

@ApplicationScoped
public class PaymentRepository implements PanacheRepositoryBase<Payment, String> {

    public Uni<Payment> findByOrderId(String orderId) {
        return find("orderId", orderId).firstResult();
    }

    public Uni<Payment> updateStatus(String orderId, Payment.PaymentStatus status) {
        return update("status = ?1, updatedAt = ?2 WHERE orderId = ?3", status, Instant.now(), orderId)
                .flatMap(count -> findByOrderId(orderId));
    }

    public Uni<Payment> setStripeCharge(String orderId, String stripeChargeId) {
        return update("stripeChargeId = ?1, status = ?2, processedAt = ?3, updatedAt = ?3 WHERE orderId = ?4",
                stripeChargeId, Payment.PaymentStatus.SUCCEEDED, Instant.now(), orderId)
                .flatMap(count -> findByOrderId(orderId));
    }
}
