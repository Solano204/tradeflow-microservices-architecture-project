package io.tradeflow.inventory.repository;

import io.tradeflow.inventory.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface InventoryRepository extends JpaRepository<Inventory, String> {

    Optional<Inventory> findByProductId(String productId);

    // Computes reserved_qty inline — available_qty = total_qty - result
    @Query(value = """
            SELECT COALESCE(SUM(r.qty), 0)
            FROM stock_reservations r
            WHERE r.inventory_id = :inventoryId
            AND r.status = 'PENDING'
            AND r.expires_at > :now
            """, nativeQuery = true)
    int computeReservedQty(@Param("inventoryId") String inventoryId, @Param("now") Instant now);
}
