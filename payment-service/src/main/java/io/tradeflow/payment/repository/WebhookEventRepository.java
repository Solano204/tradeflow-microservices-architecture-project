package io.tradeflow.payment.repository;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import io.tradeflow.payment.entity.WebhookEvent;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class  WebhookEventRepository implements PanacheRepositoryBase<WebhookEvent, String> {

    public Uni<Boolean> existsByStripeEventId(String stripeEventId) {
        return count("stripeEventId", stripeEventId).map(c -> c > 0);
    }

    public Uni<WebhookEvent> findByStripeEventId(String stripeEventId) {
        return find("stripeEventId", stripeEventId).firstResult();
    }
}
