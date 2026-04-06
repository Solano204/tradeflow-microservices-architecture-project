package io.tradeflow.order.saga;

import io.tradeflow.order.entity.Order.OrderStatus;
import io.tradeflow.order.entity.OrderEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * OrderAggregate — Event Sourcing state reconstruction.
 *
 * State is the result of replaying all order_events in version order.
 * This is a pure in-memory projection — no database writes happen here.
 *
 * Usage:
 *   List<OrderEvent> events = orderEventRepo.findByOrderIdOrderByVersionAsc(orderId);
 *   OrderAggregate aggregate = OrderAggregate.reconstruct(events);
 *   OrderStatus currentStatus = aggregate.getStatus();
 *
 * Every apply() is a pure function — same events always produce same state.
 * This enables:
 *   - Time travel: reconstruct state at any historical version
 *   - Perfect audit: state is derived from immutable event log
 *   - SAGA debugging: replay to find exactly when state diverged
 */
@Getter
@Slf4j
public class OrderAggregate {

    private String orderId;
    private OrderStatus status;
    private String buyerId;
    private String merchantId;
    private BigDecimal totalAmount;
    private String currency;
    private String reservationId;
    private String chargeId;
    private int currentVersion;

    private OrderAggregate() {}

    /** Reconstruct current state by replaying all events in order. */
    public static OrderAggregate reconstruct(List<OrderEvent> events) {
        if (events == null || events.isEmpty()) return null;

        OrderAggregate aggregate = new OrderAggregate();
        for (OrderEvent event : events) {
            aggregate.apply(event);
        }
        return aggregate;
    }

    /** Reconstruct state up to a specific version (time travel). */
    public static OrderAggregate reconstructUpTo(List<OrderEvent> events, int maxVersion) {
        OrderAggregate aggregate = new OrderAggregate();
        for (OrderEvent event : events) {
            if (event.getVersion() > maxVersion) break;
            aggregate.apply(event);
        }
        return aggregate;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event application — pure functions, each mutates in-memory state
    // ─────────────────────────────────────────────────────────────────────────

    private void apply(OrderEvent event) {
        this.currentVersion = event.getVersion();
        Map<String, Object> p = event.getEventPayload();

        switch (event.getEventType()) {
            case "ORDER_CREATED" -> {
                this.orderId   = event.getOrderId();
                this.status    = OrderStatus.CREATED;
                this.buyerId   = (String) p.get("buyer_id");
                this.merchantId = (String) p.get("merchant_id");
                this.currency  = (String) p.getOrDefault("currency", "MXN");
                Object amt = p.get("total_amount");
                this.totalAmount = amt instanceof Number n
                        ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO;
            }
            case "INVENTORY_RESERVED" -> {
                this.status = OrderStatus.INVENTORY_RESERVED;
                this.reservationId = (String) p.get("reservation_id");
            }
            case "FRAUD_SCORING_PASSED" -> this.status = OrderStatus.PAYMENT_PENDING;
            case "FRAUD_REJECTED"       -> this.status = OrderStatus.REJECTED;
            case "FRAUD_MANUAL_REVIEW"  -> this.status = OrderStatus.FRAUD_REVIEW;
            case "PAYMENT_COMPLETED"    -> {
                this.status   = OrderStatus.PAID;
                this.chargeId = (String) p.get("charge_id");
            }
            case "PAYMENT_FAILED"       -> this.status = OrderStatus.PAYMENT_FAILED;
            case "MERCHANT_ACKNOWLEDGED"-> this.status = OrderStatus.FULFILLING;
            case "ORDER_SHIPPED"        -> this.status = OrderStatus.SHIPPED;
            case "ORDER_DELIVERED"      -> this.status = OrderStatus.DELIVERED;
            case "ORDER_CANCELLED_BY_BUYER",
                 "ORDER_CANCELLED"      -> this.status = OrderStatus.CANCELLED;
            case "INVENTORY_RELEASED",
                 "INVENTORY_CONFIRMED"  -> { /* state managed by ORDER_CANCELLED etc */ }
            case "RETURN_REQUESTED"     -> this.status = OrderStatus.RETURN_REQUESTED;
            default -> log.debug("Unknown event type during replay: {}", event.getEventType());
        }
    }

    public boolean canTransitionTo(OrderStatus target) {
        if (status == null) return false;
        return switch (status) {
            case CREATED             -> target == OrderStatus.INVENTORY_RESERVED
                                     || target == OrderStatus.CANCELLED;
            case INVENTORY_RESERVED  -> target == OrderStatus.PAYMENT_PENDING
                                     || target == OrderStatus.REJECTED
                                     || target == OrderStatus.FRAUD_REVIEW;
            case FRAUD_REVIEW        -> target == OrderStatus.PAYMENT_PENDING
                                     || target == OrderStatus.REJECTED;
            case PAYMENT_PENDING     -> target == OrderStatus.PAID
                                     || target == OrderStatus.PAYMENT_FAILED;
            case PAID                -> target == OrderStatus.FULFILLING;
            case FULFILLING          -> target == OrderStatus.SHIPPED;
            case SHIPPED             -> target == OrderStatus.DELIVERED;
            case DELIVERED           -> target == OrderStatus.RETURN_REQUESTED;
            default                  -> false; // terminal states
        };
    }
}
