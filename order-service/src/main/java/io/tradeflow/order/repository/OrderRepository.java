package io.tradeflow.order.repository;

import io.tradeflow.order.entity.*;
import io.tradeflow.order.entity.Order.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {

    Optional<Order> findByIdempotencyKeyAndBuyerId(String key, String buyerId);

    Page<Order> findByBuyerIdOrderByCreatedAtDesc(String buyerId, Pageable pageable);

    @Query("SELECT o FROM Order o WHERE o.buyerId = :buyerId AND o.status = :status ORDER BY o.createdAt DESC")
    Page<Order> findByBuyerIdAndStatus(@Param("buyerId") String buyerId,
                                       @Param("status") OrderStatus status, Pageable pageable);

    @Query("SELECT o FROM Order o WHERE o.buyerId = :buyerId AND o.createdAt BETWEEN :from AND :to ORDER BY o.createdAt DESC")
    Page<Order> findByBuyerIdAndDateRange(@Param("buyerId") String buyerId,
                                          @Param("from") Instant from,
                                          @Param("to") Instant to, Pageable pageable);

    // Recovery job: find orders stuck in intermediate states
    @Query("SELECT o FROM Order o WHERE o.status = :status AND o.lastEventAt < :before")
    List<Order> findByStatusAndLastEventAtBefore(@Param("status") OrderStatus status,
                                                  @Param("before") Instant before);
}

