package io.tradeflow.catalog.repository;


import io.tradeflow.catalog.entity.CatalogOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CatalogOutboxRepository extends JpaRepository<CatalogOutbox, String> {

    @Query(value = """
            SELECT * FROM catalog_outbox
            WHERE published = false
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<CatalogOutbox> findUnpublishedWithLock(@Param("limit") int limit);

    @Modifying
    @Query("UPDATE CatalogOutbox o SET o.published = true, o.publishedAt = CURRENT_TIMESTAMP WHERE o.id IN :ids")
    void markPublished(@Param("ids") List<String> ids);
}
