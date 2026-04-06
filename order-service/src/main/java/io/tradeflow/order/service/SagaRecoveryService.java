package io.tradeflow.order.service;

import io.tradeflow.order.entity.Order;
import io.tradeflow.order.entity.OrderItem;
import io.tradeflow.order.entity.OrderOutbox;
import io.tradeflow.order.repository.OrderItemRepository;
import io.tradeflow.order.repository.OrderOutboxRepository;
import io.tradeflow.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SagaRecoveryService {

    private final OrderRepository orderRepo;
    private final OrderItemRepository itemRepo;
    private final OrderOutboxRepository outboxRepo;

    @Scheduled(fixedRate = 60_000)
    @SchedulerLock(name = "saga-recovery", lockAtLeastFor = "PT50S", lockAtMostFor = "PT55S")
    @Transactional
    public void recoverStuckSagas() {
        Instant tenMinutesAgo = Instant.now().minus(10, ChronoUnit.MINUTES);
        Instant fifteenMinutesAgo = Instant.now().minus(15, ChronoUnit.MINUTES);
        int recovered = 0;

        // ── STUCK IN CREATED — inventory reserve command never delivered ───────
        List<Order> stuckAtCreated = orderRepo.findByStatusAndLastEventAtBefore(
                Order.OrderStatus.CREATED, tenMinutesAgo);

        for (Order o : stuckAtCreated) {
            List<OrderItem> items = itemRepo.findByOrderId(o.getId());
            if (items.isEmpty()) continue;
            OrderItem first = items.get(0);

            log.warn("SAGA recovery: re-emitting cmd.reserve-inventory for orderId={}", o.getId());
            outboxRepo.save(OrderOutbox.builder()
                    .eventType("cmd.reserve-inventory")
                    .payload(Map.of(
                            "order_id",       o.getId(),
                            "product_id",     first.getProductId(),
                            "qty",            first.getQty(),
                            "buyer_id",       o.getBuyerId(),
                            "idempotency_key", o.getId() + "-reserve",
                            "recovery",       true,
                            "timestamp",      Instant.now().toString()
                    ))
                    .aggregateId(o.getId())
                    .build());
            recovered++;
        }

        // ── STUCK IN INVENTORY_RESERVED — fraud command never delivered ────────
        List<Order> stuckAtInventory = orderRepo.findByStatusAndLastEventAtBefore(
                Order.OrderStatus.INVENTORY_RESERVED, tenMinutesAgo);

        for (Order o : stuckAtInventory) {
            List<OrderItem> items = itemRepo.findByOrderId(o.getId());

            log.warn("SAGA recovery: re-emitting cmd.score-transaction for orderId={}", o.getId());
            outboxRepo.save(OrderOutbox.builder()
                    .eventType("cmd.score-transaction")
                    .payload(Map.of(
                            "order_id",       o.getId(),
                            "buyer_id",       o.getBuyerId(),
                            "amount",         o.getTotalAmount().toString(),
                            "currency",       o.getCurrency(),
                            "merchant_id",    o.getMerchantId(),
                            "product_ids",    items.stream().map(OrderItem::getProductId).toList(),
                            "idempotency_key", o.getId() + "-fraud",
                            "recovery",       true,
                            "timestamp",      Instant.now().toString()
                    ))
                    .aggregateId(o.getId())
                    .build());
            recovered++;
        }

        // ── STUCK IN PAYMENT_PENDING > 15 min — timeout compensation ──────────
        List<Order> stuckAtPayment = orderRepo.findByStatusAndLastEventAtBefore(
                Order.OrderStatus.PAYMENT_PENDING, fifteenMinutesAgo);

        for (Order o : stuckAtPayment) {
            log.warn("SAGA recovery: payment timeout for orderId={}, initiating compensation", o.getId());

            // Release inventory (compensation)
            if (o.getReservationId() != null) {
                outboxRepo.save(OrderOutbox.builder()
                        .eventType("cmd.release-inventory")
                        .payload(Map.of(
                                "order_id",       o.getId(),
                                "reservation_id", o.getReservationId(),
                                "reason",         "PAYMENT_TIMEOUT",
                                "idempotency_key", o.getId() + "-release-timeout"
                        ))
                        .aggregateId(o.getId())
                        .build());
            }

            // Notify buyer
            outboxRepo.save(OrderOutbox.builder()
                    .eventType("notification.payment-failed")
                    .payload(Map.of("buyer_id", o.getBuyerId(), "order_id", o.getId(),
                            "reason", "PAYMENT_TIMEOUT"))
                    .aggregateId(o.getId())
                    .build());

            recovered++;
        }

        if (recovered > 0) {
            log.info("SAGA recovery: re-drove {} stuck orders", recovered);
        }
    }
}
