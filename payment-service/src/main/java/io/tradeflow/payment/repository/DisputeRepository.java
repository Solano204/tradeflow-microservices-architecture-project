package io.tradeflow.payment.repository;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import io.tradeflow.payment.entity.Dispute;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class DisputeRepository implements PanacheRepositoryBase<Dispute, String> {

    public Uni<Dispute> findByStripeDisputeId(String stripeDisputeId) {
        return find("stripeDisputeId", stripeDisputeId).firstResult();
    }

    public Uni<List<Dispute>> findByOrderId(String orderId) {
        return list("orderId", orderId);
    }
}
