package io.tradeflow.fraud.repository;

import io.tradeflow.fraud.entity.FraudVector;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface FraudVectorRepository extends MongoRepository<FraudVector, String> {

    Optional<FraudVector> findByOrderId(String orderId);

    List<FraudVector> findByBuyerIdOrderByScoredAtDesc(String buyerId);

    @Query("{ 'buyer_id': ?0, 'scored_at': { $gte: ?1 } }")
    List<FraudVector> findRecentByBuyerId(String buyerId, Instant since);
}
