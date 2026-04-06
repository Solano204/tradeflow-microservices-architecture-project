package io.tradeflow.inventory.repository;

import io.tradeflow.inventory.entity.*;
import io.tradeflow.inventory.entity.StockReservation.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockReservationRepository extends JpaRepository<StockReservation, String> {

    Optional<StockReservation> findByIdAndOrderId(String id, String orderId);

    // Idempotency check — find existing PENDING reservation for this order
    Optional<StockReservation> findByOrderIdAndInventoryIdAndStatus(
            String orderId, String inventoryId, ReservationStatus status);

    // Idempotency check by key
    Optional<StockReservation> findByIdempotencyKey(String idempotencyKey);

    // Active reservations for a given inventory (for rich GET response)
    @Query("""
            SELECT r FROM StockReservation r
            WHERE r.inventoryId = :inventoryId
            AND r.status = 'PENDING'
            AND r.expiresAt > :now
            ORDER BY r.createdAt DESC
            """)
    List<StockReservation> findActiveByInventoryId(
            @Param("inventoryId") String inventoryId,
            @Param("now") Instant now);

    // TTL cleanup job: find all PENDING reservations past their expiry
    @Query(value = """
            SELECT * FROM stock_reservations
            WHERE status = 'PENDING'
            AND expires_at < :now
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<StockReservation> findExpiredWithLock(@Param("now") Instant now, @Param("limit") int limit);
}

