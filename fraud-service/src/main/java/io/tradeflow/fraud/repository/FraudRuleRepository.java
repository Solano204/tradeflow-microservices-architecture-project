package io.tradeflow.fraud.repository;

import io.tradeflow.fraud.entity.FraudRule;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface FraudRuleRepository extends MongoRepository<FraudRule, String> {

    List<FraudRule> findByActiveTrueOrderByPriorityAsc();

    Optional<FraudRule> findByRuleName(String ruleName);

    @Query("{ 'active': true, 'expires_at': { $lt: ?0 } }")
    List<FraudRule> findExpiredActiveRules(Instant now);
}
