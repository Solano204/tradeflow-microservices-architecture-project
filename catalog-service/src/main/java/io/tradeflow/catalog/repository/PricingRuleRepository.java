package io.tradeflow.catalog.repository;

import io.tradeflow.catalog.entity.PricingRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PricingRuleRepository extends JpaRepository<PricingRule, String> {
    List<PricingRule> findByProductId(String productId);
    void deleteByProductId(String productId);
}