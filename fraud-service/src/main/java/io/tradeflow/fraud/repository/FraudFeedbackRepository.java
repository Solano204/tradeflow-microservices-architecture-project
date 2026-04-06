package io.tradeflow.fraud.repository;

import io.tradeflow.fraud.entity.FraudFeedback;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface FraudFeedbackRepository extends MongoRepository<FraudFeedback, String> {

    Optional<FraudFeedback> findByOrderId(String orderId);

    /** Used by weekly Spring Batch job — find unlabeled samples to train on */
    List<FraudFeedback> findByUsedInModelVersionIsNull();

    long countByWasFalseNegativeTrueAndConfirmedAtAfter(Instant since);
}
