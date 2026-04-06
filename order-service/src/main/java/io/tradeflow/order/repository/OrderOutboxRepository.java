package io.tradeflow.order.repository;

import io.tradeflow.order.entity.OrderOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderOutboxRepository extends JpaRepository<OrderOutbox, String> {

    @Query(value = """
            SELECT * FROM order_outbox
            WHERE published = false
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OrderOutbox> findUnpublishedWithLock(@Param("limit") int limit);

    @Modifying
    @Query("UPDATE OrderOutbox o SET o.published = true, o.publishedAt = CURRENT_TIMESTAMP WHERE o.id IN :ids")
    void markPublished(@Param("ids") List<String> ids);
}
