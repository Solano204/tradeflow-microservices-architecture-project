package io.tradeflow.payment.repository;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoRepository;
import io.quarkus.panache.common.Parameters;
import io.smallrye.mutiny.Uni;
import io.tradeflow.payment.entity.*;
import io.tradeflow.payment.entity.DunningCase.DunningStage;
import io.tradeflow.payment.entity.Payment.PaymentStatus;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class DunningRepository implements PanacheRepositoryBase<DunningCase, String> {

    /** Fetch active dunning cases due for action NOW */
    public Uni<List<DunningCase>> findActiveByNextActionBefore(Instant before) {
        return list("currentStage NOT IN ?1 AND nextActionDate <= ?2",
                List.of(DunningStage.RESOLVED, DunningStage.TERMINATED),
                before);
    }

    public Uni<DunningCase> findByMerchantId(String merchantId) {
        return find("merchantId = ?1 AND currentStage NOT IN ?2",
                merchantId,
                List.of(DunningStage.RESOLVED, DunningStage.TERMINATED))
                .firstResult();
    }
}

