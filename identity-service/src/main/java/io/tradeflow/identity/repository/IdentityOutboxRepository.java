package io.tradeflow.identity.repository;

import io.tradeflow.identity.entity.IdentityOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface IdentityOutboxRepository extends JpaRepository<IdentityOutbox, String> {

    @Query(value = """
            SELECT * FROM identity_outbox
            WHERE published = false
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<IdentityOutbox> findUnpublishedWithLock(@Param("limit") int limit);

    @Modifying
    @Query("UPDATE IdentityOutbox o SET o.published = true, o.publishedAt = CURRENT_TIMESTAMP WHERE o.id IN :ids")
    void markPublished(@Param("ids") List<String> ids);
}
