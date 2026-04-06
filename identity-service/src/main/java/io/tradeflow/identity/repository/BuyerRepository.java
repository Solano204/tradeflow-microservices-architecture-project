package io.tradeflow.identity.repository;

import io.tradeflow.identity.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

// ─── PostgreSQL ───────────────────────────────────────────────────────────────

public interface BuyerRepository extends JpaRepository<Buyer, String> {
    Optional<Buyer> findByEmail(String email);
    boolean existsByEmail(String email);
}

// ─── MongoDB ──────────────────────────────────────────────────────────────────

