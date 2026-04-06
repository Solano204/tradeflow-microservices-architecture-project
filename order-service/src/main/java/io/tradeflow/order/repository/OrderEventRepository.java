package io.tradeflow.order.repository;

import io.tradeflow.order.entity.OrderEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderEventRepository extends JpaRepository<OrderEvent, String> {

    // The event replay query — core of event sourcing reconstruction
    List<OrderEvent> findByOrderIdOrderByVersionAsc(String orderId);

    // Next version for this order (MAX + 1)
    @Query("SELECT COALESCE(MAX(e.version), 0) FROM OrderEvent e WHERE e.orderId = :orderId")
    int getMaxVersion(@Param("orderId") String orderId);

    // Timeline query for GET /orders/{id} (lightweight — just type + timestamp)
    @Query("SELECT e FROM OrderEvent e WHERE e.orderId = :orderId ORDER BY e.version ASC")
    List<OrderEvent> findTimeline(@Param("orderId") String orderId);
}
