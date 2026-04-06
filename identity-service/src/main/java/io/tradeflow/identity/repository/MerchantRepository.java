package io.tradeflow.identity.repository;

import io.tradeflow.identity.entity.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MerchantRepository extends JpaRepository<Merchant, String> {
    Optional<Merchant> findByContactEmail(String email);
    Optional<Merchant> findByTaxId(String taxId);
    boolean existsByTaxId(String taxId);

    @Query("SELECT m.status FROM Merchant m WHERE m.id = :id")
    Optional<String> findStatusById(@Param("id") String id);
}
