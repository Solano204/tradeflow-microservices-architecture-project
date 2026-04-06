package io.tradeflow.inventory.repository;

import io.tradeflow.inventory.entity.InventoryOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InventoryOutboxRepository extends JpaRepository<InventoryOutbox, String> {

    @Query(value = """
            SELECT * FROM inventory_outbox
            WHERE published = false
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<InventoryOutbox> findUnpublishedWithLock(@Param("limit") int limit);

    @Modifying
    @Query("UPDATE InventoryOutbox o SET o.published = true, o.publishedAt = CURRENT_TIMESTAMP WHERE o.id IN :ids")
    void markPublished(@Param("ids") List<String> ids);
}
