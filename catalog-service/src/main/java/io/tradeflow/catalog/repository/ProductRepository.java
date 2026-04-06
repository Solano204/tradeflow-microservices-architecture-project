package io.tradeflow.catalog.repository;

import io.tradeflow.catalog.entity.*;
import io.tradeflow.catalog.entity.Product.ProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

// ─────────────────────────────────────────────────────────────────────────────
// PostgreSQL Repositories
// ─────────────────────────────────────────────────────────────────────────────

public interface ProductRepository extends JpaRepository<Product, String> {

    Page<Product> findByCategoryIdAndStatusAndPriceBetween(
            String categoryId, ProductStatus status,
            BigDecimal minPrice, BigDecimal maxPrice,
            Pageable pageable);

    Page<Product> findByCategoryIdAndStatus(String categoryId, ProductStatus status, Pageable pageable);

    Page<Product> findByMerchantIdAndStatus(String merchantId, ProductStatus status, Pageable pageable);

    Page<Product> findByMerchantId(String merchantId, Pageable pageable);

    // Top-N hot products for cache pre-warming at startup
    @Query("SELECT p FROM Product p WHERE p.status = 'ACTIVE' ORDER BY p.salesVolume DESC")
    List<Product> findTopBySalesVolume(Pageable pageable);

    // Campaign tick: find campaigns ready to activate
    @Query("""
            SELECT p FROM Product p
            WHERE p.priceMode = 'PROMOTIONAL'
            AND p.promotionalStart <= :now
            AND p.promotionalEnd > :now
            AND p.status = 'ACTIVE'
            """)
    List<Product> findReadyToPromote(@Param("now") Instant now);

    // Campaign tick: find expired campaigns to revert
    @Query("""
            SELECT p FROM Product p
            WHERE p.priceMode = 'PROMOTIONAL'
            AND p.promotionalEnd <= :now
            """)
    List<Product> findExpiredPromos(@Param("now") Instant now);

    List<Product> findByMerchantIdAndStatusIn(String merchantId, List<ProductStatus> statuses);
}





// ─────────────────────────────────────────────────────────────────────────────
// MongoDB Repository
// ─────────────────────────────────────────────────────────────────────────────

