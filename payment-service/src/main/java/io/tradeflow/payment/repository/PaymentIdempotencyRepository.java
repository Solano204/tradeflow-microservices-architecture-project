package io.tradeflow.payment.repository;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import io.tradeflow.payment.entity.PaymentIdempotency;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;

@ApplicationScoped
public class  PaymentIdempotencyRepository implements PanacheRepositoryBase<PaymentIdempotency, String> {

    public Uni<PaymentIdempotency> findByKey(String key) {
        return findById(key);
    }

    public Uni<PaymentIdempotency> findValidByKey(String key) {
        return find("idempotencyKey = ?1 AND expiresAt > ?2", key, Instant.now()).firstResult();
    }
}
