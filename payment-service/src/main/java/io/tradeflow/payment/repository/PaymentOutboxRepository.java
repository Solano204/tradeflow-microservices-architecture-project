package io.tradeflow.payment.repository;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import io.tradeflow.payment.entity.PaymentOutbox;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class PaymentOutboxRepository implements PanacheRepositoryBase<PaymentOutbox, String> {

    public Uni<List<PaymentOutbox>> findUnpublished(int limit) {
        // Reactive Panache doesn't support FOR UPDATE SKIP LOCKED via getEntityManager()
        // Use simple query — single-pod dev mode, multi-pod handled by outbox relay timing
        return list("published = false ORDER BY createdAt ASC")
                .map(all -> all.stream().limit(limit).toList());
    }

    public Uni<Integer> markPublished(List<String> ids) {
        return update("published = true, publishedAt = ?1 WHERE id IN ?2", Instant.now(), ids);
    }
}
