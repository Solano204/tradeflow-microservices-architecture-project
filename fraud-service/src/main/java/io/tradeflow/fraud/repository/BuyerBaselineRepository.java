package io.tradeflow.fraud.repository;

import io.tradeflow.fraud.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

// ─────────────────────────────────────────────────────────────────────────────
// MONGODB REPOSITORIES
// ─────────────────────────────────────────────────────────────────────────────

@Repository
public interface BuyerBaselineRepository extends MongoRepository<BuyerBaseline, String> {

    Optional<BuyerBaseline> findByBuyerId(String buyerId);
}

// ─────────────────────────────────────────────────────────────────────────────
// POSTGRESQL REPOSITORIES
// ─────────────────────────────────────────────────────────────────────────────

