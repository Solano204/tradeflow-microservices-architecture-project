package io.tradeflow.fraud.repository;

import io.tradeflow.fraud.entity.FraudScoreAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FraudScoreAuditRepository extends JpaRepository<FraudScoreAudit, String> {

    Optional<FraudScoreAudit> findByOrderId(String orderId);

    List<FraudScoreAudit> findByBuyerIdOrderByScoredAtDesc(String buyerId);
}
