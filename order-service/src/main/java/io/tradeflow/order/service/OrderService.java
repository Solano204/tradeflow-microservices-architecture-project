package io.tradeflow.order.service;

import io.micrometer.observation.annotation.Observed;
import io.tradeflow.order.dto.OrderDtos.*;
import io.tradeflow.order.entity.*;
import io.tradeflow.order.entity.Order.OrderStatus;
import io.tradeflow.order.repository.*;
import io.tradeflow.order.saga.OrderAggregate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * OrderService — SAGA orchestrator and HTTP endpoint handler.
 *
 * TRACING (@Observed):
 * ─────────────────────────────────────────────────────────────────────────────
 * The four SAGA state-machine methods (onInventoryReserved, onFraudScoreComputed,
 * onPaymentProcessed, onPaymentFailed) are annotated with @Observed.
 *
 * Each annotation creates a named child span in Zipkin under the Kafka consumer
 * span that called it. This means in Zipkin you can see:
 *
 *   kafka.consume inventory.events        [traceId=abc123]
 *     └── saga.order.inventory-reserved   [traceId=abc123]
 *           └── postgresql INSERT order_events
 *           └── postgresql UPDATE orders
 *           └── kafka send cmd.score-transaction  ← header: traceparent=abc123
 *
 * The traceId never changes. Every DB write and every outgoing Kafka message
 * is a child of the original HTTP request that triggered POST /orders.
 *
 * CRITICAL FIX (unchanged from original):
 * ─────────────────────────────────────────────────────────────────────────────
 * event_type missing from notification outbox payloads — fixed by adding
 * "event_type" key to every notification and order event payload.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepo;
    private final OrderItemRepository itemRepo;
    private final OrderEventRepository eventRepo;
    private final OrderOutboxRepository outboxRepo;
    private final InternalServiceClients clients;

    private static final BigDecimal PRICE_DRIFT_TOLERANCE = new BigDecimal("0.01");

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 1 — POST /orders
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public CreateOrderResponse createOrder(CreateOrderRequest req) {
        Optional<Order> existing = orderRepo.findByIdempotencyKeyAndBuyerId(
                req.idempotencyKey(), req.buyerId());
        if (existing.isPresent()) {
            Order o = existing.get();
            log.debug("Create order idempotent hit: orderId={}", o.getId());
            return buildCreateResponse(o, itemRepo.findByOrderId(o.getId()));
        }

        preValidate(req);

        String primaryMerchantId = req.items().get(0).merchantId();
        BigDecimal totalAmount = req.items().stream()
                .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.qty())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String orderId = "ord_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        Order order = Order.builder()
                .id(orderId).buyerId(req.buyerId()).merchantId(primaryMerchantId)
                .status(OrderStatus.CREATED).totalAmount(totalAmount).currency(req.currency())
                .shippingAddress(shippingAddressToMap(req.shippingAddress()))
                .paymentMethodToken(req.paymentMethodToken())
                .idempotencyKey(req.idempotencyKey())
                .lastEventVersion(1).lastEventAt(Instant.now())
                .build();
        orderRepo.save(order);

        List<OrderItem> savedItems = new ArrayList<>();
        for (OrderItemRequest item : req.items()) {
            OrderItem oi = OrderItem.builder()
                    .orderId(orderId).productId(item.productId())
                    .merchantId(item.merchantId()).qty(item.qty())
                    .unitPrice(item.unitPrice())
                    .productTitle("Product " + item.productId())
                    .currency(req.currency()).build();
            savedItems.add(itemRepo.save(oi));
        }

        appendEvent(orderId, "ORDER_CREATED", 1, buildCreatedPayload(req, totalAmount), null);

        OrderItemRequest firstItem = req.items().get(0);
        outboxRepo.save(OrderOutbox.builder()
                .eventType("cmd.reserve-inventory")
                .payload(Map.of(
                        "order_id",        orderId,
                        "product_id",      firstItem.productId(),
                        "qty",             firstItem.qty(),
                        "buyer_id",        req.buyerId(),
                        "idempotency_key", orderId + "-reserve",
                        "timestamp",       Instant.now().toString()
                ))
                .aggregateId(orderId)
                .build());

        log.info("Order created: orderId={}, buyerId={}, total={} {}",
                orderId, req.buyerId(), totalAmount, req.currency());
        return buildCreateResponse(order, savedItems);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 2 — GET /orders/{id}
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public OrderDetailResponse getOrder(String orderId) {
        Order order = findOrder(orderId);
        List<OrderItem> items = itemRepo.findByOrderId(orderId);
        List<OrderEvent> events = eventRepo.findTimeline(orderId);

        List<SagaTimelineEntry> timeline = events.stream()
                .map(e -> new SagaTimelineEntry(e.getEventType(), e.getOccurredAt().toString()))
                .toList();

        OrderEvent latest = events.isEmpty() ? null : events.get(events.size() - 1);
        CurrentStateDto state = latest != null
                ? new CurrentStateDto(order.getStatus().name(),
                latest.getOccurredAt().toString(), latest.getVersion())
                : new CurrentStateDto(order.getStatus().name(),
                order.getCreatedAt().toString(), 0);

        List<OrderItemResponse> itemDtos = items.stream()
                .map(i -> new OrderItemResponse(i.getProductId(), i.getProductTitle(),
                        i.getQty(), i.getUnitPrice(), i.subtotal()))
                .toList();

        return new OrderDetailResponse(orderId, order.getStatus().name(), order.getBuyerId(),
                order.getMerchantId(), order.getTotalAmount(), order.getCurrency(), itemDtos,
                mapToShippingDto(order.getShippingAddress()), state, timeline,
                order.getCreatedAt(), order.getDeliveredAt());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 3 — GET /orders/{id}/history
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public OrderHistoryResponse getOrderHistory(String orderId) {
        findOrder(orderId);
        List<OrderEvent> events = eventRepo.findByOrderIdOrderByVersionAsc(orderId);
        List<OrderEventDto> dtos = events.stream()
                .map(e -> new OrderEventDto(e.getVersion(), e.getEventType(),
                        e.getOccurredAt().toString(), e.getCausationId(), e.getEventPayload()))
                .toList();
        return new OrderHistoryResponse(orderId, events.size(), dtos);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 4 — GET /buyers/{buyerId}/orders
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public BuyerOrdersResponse getBuyerOrders(String buyerId, String statusFilter,
                                              int page, int size) {
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(page, size);
        org.springframework.data.domain.Page<Order> orderPage = statusFilter != null
                ? orderRepo.findByBuyerIdAndStatus(buyerId, OrderStatus.valueOf(statusFilter), pageable)
                : orderRepo.findByBuyerIdOrderByCreatedAtDesc(buyerId, pageable);

        List<OrderSummaryDto> summaries = orderPage.getContent().stream().map(o -> {
            List<OrderItem> items = itemRepo.findByOrderId(o.getId());
            String summary = items.isEmpty() ? "" :
                    items.get(0).getProductTitle() +
                            (items.size() > 1 ? " +" + (items.size() - 1) + " more" : "");
            return new OrderSummaryDto(o.getId(), o.getStatus().name(), o.getTotalAmount(),
                    o.getCurrency(), summary, o.getCreatedAt(), o.getDeliveredAt());
        }).toList();

        return new BuyerOrdersResponse(buyerId, orderPage.getTotalElements(), page, size, summaries);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 5 — POST /orders/{id}/cancel
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void cancelOrder(String orderId, CancelOrderRequest req, String buyerId) {
        Order order = findOrder(orderId);
        if (!order.getBuyerId().equals(buyerId)) throw new ForbiddenException("Not your order");

        OrderAggregate aggregate = reconstructAggregate(orderId);
        if (aggregate.getStatus() != OrderStatus.CREATED) {
            throw new ConflictException("Cannot cancel at this stage: " + aggregate.getStatus());
        }

        int nextVersion = nextVersion(orderId);
        appendEvent(orderId, "ORDER_CANCELLED_BY_BUYER", nextVersion, Map.of(
                "reason",       req.reason(),
                "buyer_note",   req.buyerNote() != null ? req.buyerNote() : "",
                "cancelled_at", Instant.now().toString()
        ), null);

        order.setStatus(OrderStatus.CANCELLED);
        order.setLastEventVersion(nextVersion);
        order.setLastEventAt(Instant.now());
        orderRepo.save(order);

        outboxRepo.save(OrderOutbox.builder()
                .eventType("event.order-cancelled")
                .payload(Map.of(
                        "event_type", "event.order-cancelled",
                        "order_id",   orderId,
                        "buyer_id",   buyerId,
                        "reason",     req.reason(),
                        "timestamp",  Instant.now().toString()
                ))
                .aggregateId(orderId)
                .build());

        log.info("Order cancelled by buyer: orderId={}, reason={}", orderId, req.reason());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 6 — POST /orders/{id}/fulfillment/acknowledge
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void acknowledgeFulfillment(String orderId, AcknowledgeRequest req, String merchantId) {
        Order order = validateMerchantOwnership(orderId, merchantId);
        requireStatus(order, OrderStatus.PAID, "acknowledge fulfillment");

        int nextVersion = nextVersion(orderId);
        appendEvent(orderId, "MERCHANT_ACKNOWLEDGED", nextVersion, Map.of(
                "merchant_user_id",    req.merchantUserId(),
                "estimated_ship_date", req.estimatedShipDate() != null ? req.estimatedShipDate().toString() : "",
                "fulfillment_notes",   req.fulfillmentNotes() != null ? req.fulfillmentNotes() : "",
                "acknowledged_at",     Instant.now().toString()
        ), null);

        order.setStatus(OrderStatus.FULFILLING);
        order.setLastEventVersion(nextVersion);
        order.setLastEventAt(Instant.now());
        orderRepo.save(order);

        outboxRepo.save(OrderOutbox.builder()
                .eventType("notification.order-being-prepared")
                .payload(Map.of(
                        "event_type",          "notification.order-being-prepared",
                        "buyer_id",            order.getBuyerId(),
                        "order_id",            orderId,
                        "merchant_id",         merchantId,
                        "estimated_ship_date", req.estimatedShipDate() != null
                                ? req.estimatedShipDate().toString() : ""
                ))
                .aggregateId(orderId)
                .build());

        log.info("Order acknowledged: orderId={}, merchantId={}", orderId, merchantId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 7 — POST /orders/{id}/shipment
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void markShipped(String orderId, ShipmentRequest req, String merchantId) {
        Order order = validateMerchantOwnership(orderId, merchantId);
        requireStatus(order, OrderStatus.FULFILLING, "mark as shipped");

        int nextVersion = nextVersion(orderId);
        appendEvent(orderId, "ORDER_SHIPPED", nextVersion, Map.of(
                "courier",            req.courier(),
                "tracking_number",    req.trackingNumber(),
                "tracking_url",       req.trackingUrl() != null ? req.trackingUrl() : "",
                "shipped_at",         req.shippedAt() != null ? req.shippedAt().toString() : Instant.now().toString(),
                "estimated_delivery", req.estimatedDelivery() != null ? req.estimatedDelivery().toString() : ""
        ), null);

        order.setStatus(OrderStatus.SHIPPED);
        order.setTrackingNumber(req.trackingNumber());
        order.setLastEventVersion(nextVersion);
        order.setLastEventAt(Instant.now());
        orderRepo.save(order);

        outboxRepo.save(OrderOutbox.builder()
                .eventType("notification.order-shipped")
                .payload(Map.of(
                        "event_type",         "notification.order-shipped",
                        "buyer_id",           order.getBuyerId(),
                        "order_id",           orderId,
                        "courier",            req.courier(),
                        "tracking_number",    req.trackingNumber(),
                        "tracking_url",       req.trackingUrl() != null ? req.trackingUrl() : "",
                        "estimated_delivery", req.estimatedDelivery() != null
                                ? req.estimatedDelivery().toString() : ""
                ))
                .aggregateId(orderId)
                .build());

        log.info("Order shipped: orderId={}, tracking={}", orderId, req.trackingNumber());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 8 — POST /orders/{id}/delivery
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void markDelivered(String orderId, DeliveryRequest req) {
        Order order = findOrder(orderId);
        requireStatus(order, OrderStatus.SHIPPED, "mark as delivered");

        int nextVersion = nextVersion(orderId);
        appendEvent(orderId, "ORDER_DELIVERED", nextVersion, Map.of(
                "delivered_at",          req.deliveredAt().toString(),
                "delivery_confirmation", req.deliveryConfirmation() != null ? req.deliveryConfirmation() : "",
                "recipient_name",        req.recipientName() != null ? req.recipientName() : "",
                "proof_of_delivery_url", req.proofOfDeliveryUrl() != null ? req.proofOfDeliveryUrl() : ""
        ), "courier-webhook");

        order.setStatus(OrderStatus.DELIVERED);
        order.setDeliveredAt(req.deliveredAt());
        order.setLastEventVersion(nextVersion);
        order.setLastEventAt(Instant.now());
        orderRepo.save(order);

        outboxRepo.save(OrderOutbox.builder()
                .eventType("notification.order-delivered")
                .payload(Map.of(
                        "event_type",   "notification.order-delivered",
                        "buyer_id",     order.getBuyerId(),
                        "order_id",     orderId,
                        "delivered_at", req.deliveredAt().toString()
                ))
                .aggregateId(orderId)
                .build());

        outboxRepo.save(OrderOutbox.builder()
                .eventType("event.order-delivered-for-payout")
                .payload(Map.of(
                        "event_type",   "event.order-delivered-for-payout",
                        "merchant_id",  order.getMerchantId(),
                        "order_id",     orderId,
                        "amount",       order.getTotalAmount().toString(),
                        "currency",     order.getCurrency(),
                        "delivered_at", req.deliveredAt().toString()
                ))
                .aggregateId(orderId)
                .build());

        log.info("Order delivered: orderId={}, deliveredAt={}", orderId, req.deliveredAt());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 9 — POST /orders/{id}/return
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void requestReturn(String orderId, ReturnRequest req, String buyerId) {
        Order order = findOrder(orderId);
        if (!order.getBuyerId().equals(buyerId)) throw new ForbiddenException("Not your order");
        requireStatus(order, OrderStatus.DELIVERED, "request return");

        int nextVersion = nextVersion(orderId);
        appendEvent(orderId, "RETURN_REQUESTED", nextVersion, Map.of(
                "reason",            req.reason(),
                "description",       req.description() != null ? req.description() : "",
                "evidence_urls",     req.evidenceUrls() != null ? req.evidenceUrls() : List.of(),
                "return_preference", req.returnPreference() != null ? req.returnPreference() : "REFUND",
                "requested_at",      Instant.now().toString()
        ), null);

        order.setStatus(OrderStatus.RETURN_REQUESTED);
        order.setLastEventVersion(nextVersion);
        order.setLastEventAt(Instant.now());
        orderRepo.save(order);

        outboxRepo.save(OrderOutbox.builder()
                .eventType("notification.return-request-to-merchant")
                .payload(Map.of(
                        "event_type",  "notification.return-request-to-merchant",
                        "merchant_id", order.getMerchantId(),
                        "order_id",    orderId,
                        "reason",      req.reason()
                ))
                .aggregateId(orderId)
                .build());

        outboxRepo.save(OrderOutbox.builder()
                .eventType("notification.return-confirmation-to-buyer")
                .payload(Map.of(
                        "event_type", "notification.return-confirmation-to-buyer",
                        "buyer_id",   buyerId,
                        "order_id",   orderId
                ))
                .aggregateId(orderId)
                .build());

        log.info("Return requested: orderId={}, reason={}", orderId, req.reason());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 10 — GET /internal/orders/{id}/status
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public InternalOrderStatusResponse getInternalStatus(String orderId) {
        Order order = findOrder(orderId);
        return new InternalOrderStatusResponse(orderId, order.getStatus().name(),
                order.getBuyerId(), order.getTotalAmount(), order.getLastEventVersion());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SAGA state machine — called by Kafka consumers in OrderSagaConsumers
    //
    // TRACING NOTE:
    // These methods are annotated with @Observed which creates a named child span
    // under the Kafka consumer span. All DB operations inside each method appear
    // as children of that span — and all still share the same traceId that was
    // originally created by the POST /orders HTTP request.
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    @Observed(name = "saga.order.inventory-reserved",
            contextualName = "inventory-reserved")
    public void onInventoryReserved(String orderId, String reservationId, int qty, String causationId) {
        Order order = findOrder(orderId);
        if (order.getStatus() != OrderStatus.CREATED) {
            log.debug("inventory.reserved idempotent skip: orderId={}", orderId);
            return;
        }

        int nextVersion = nextVersion(orderId);
        appendEvent(orderId, "INVENTORY_RESERVED", nextVersion,
                Map.of("reservation_id", reservationId, "qty", qty,
                        "timestamp", Instant.now().toString()), causationId);

        order.setStatus(OrderStatus.INVENTORY_RESERVED);
        order.setReservationId(reservationId);
        order.setLastEventVersion(nextVersion);
        order.setLastEventAt(Instant.now());
        orderRepo.save(order);

        List<OrderItem> items = itemRepo.findByOrderId(orderId);
        outboxRepo.save(OrderOutbox.builder()
                .eventType("cmd.score-transaction")
                .payload(Map.of(
                        "order_id",        orderId,
                        "buyer_id",        order.getBuyerId(),
                        "amount",          order.getTotalAmount().toString(),
                        "currency",        order.getCurrency(),
                        "merchant_id",     order.getMerchantId(),
                        "product_ids",     items.stream().map(OrderItem::getProductId).toList(),
                        "idempotency_key", orderId + "-fraud",
                        "timestamp",       Instant.now().toString()
                ))
                .aggregateId(orderId)
                .build());

        log.info("SAGA: INVENTORY_RESERVED → cmd.score-transaction: orderId={}", orderId);
    }

    @Transactional
    @Observed(name = "saga.order.fraud-score-computed",
            contextualName = "fraud-score-computed")
    public void onFraudScoreComputed(String orderId, double score, String decision, String causationId) {
        Order order = findOrder(orderId);
        if (order.getStatus() != OrderStatus.INVENTORY_RESERVED) {
            log.debug("fraud.score.computed idempotent skip: orderId={}", orderId);
            return;
        }

        int nextVersion = nextVersion(orderId);

        if ("APPROVED".equals(decision)) {
            appendEvent(orderId, "FRAUD_SCORING_PASSED", nextVersion,
                    Map.of("score", score, "decision", decision, "timestamp", Instant.now().toString()),
                    causationId);

            order.setStatus(OrderStatus.PAYMENT_PENDING);
            order.setLastEventVersion(nextVersion);
            order.setLastEventAt(Instant.now());
            orderRepo.save(order);

            outboxRepo.save(OrderOutbox.builder()
                    .eventType("cmd.charge-payment")
                    .payload(Map.of(
                            "order_id",             orderId,
                            "buyer_id",             order.getBuyerId(),
                            "amount",               order.getTotalAmount().toString(),
                            "currency",             order.getCurrency(),
                            "payment_method_token", order.getPaymentMethodToken(),
                            "merchant_id",          order.getMerchantId(),
                            "idempotency_key",      orderId
                    ))
                    .aggregateId(orderId)
                    .build());

            log.info("SAGA: FRAUD_APPROVED → cmd.charge-payment: orderId={}", orderId);

        } else if ("BLOCKED".equals(decision)) {
            appendEvent(orderId, "FRAUD_REJECTED", nextVersion,
                    Map.of("score", score, "reason", "HIGH_RISK", "timestamp", Instant.now().toString()),
                    causationId);

            order.setStatus(OrderStatus.REJECTED);
            order.setLastEventVersion(nextVersion);
            order.setLastEventAt(Instant.now());
            orderRepo.save(order);

            outboxRepo.save(OrderOutbox.builder()
                    .eventType("cmd.release-inventory")
                    .payload(Map.of("order_id", orderId, "reservation_id", order.getReservationId(),
                            "reason", "FRAUD_REJECTED", "idempotency_key", orderId + "-release"))
                    .aggregateId(orderId)
                    .build());

            outboxRepo.save(OrderOutbox.builder()
                    .eventType("notification.order-rejected")
                    .payload(Map.of(
                            "event_type", "notification.order-rejected",
                            "buyer_id",   order.getBuyerId(),
                            "order_id",   orderId
                    ))
                    .aggregateId(orderId)
                    .build());

            outboxRepo.save(OrderOutbox.builder()
                    .eventType("event.order-rejected")
                    .payload(Map.of(
                            "event_type", "event.order-rejected",
                            "order_id",   orderId,
                            "buyer_id",   order.getBuyerId(),
                            "timestamp",  Instant.now().toString()
                    ))
                    .aggregateId(orderId)
                    .build());

            log.warn("SAGA: FRAUD_REJECTED: orderId={}", orderId);

        } else {
            appendEvent(orderId, "FRAUD_MANUAL_REVIEW", nextVersion,
                    Map.of("score", score, "timestamp", Instant.now().toString()), causationId);
            order.setStatus(OrderStatus.FRAUD_REVIEW);
            order.setLastEventVersion(nextVersion);
            order.setLastEventAt(Instant.now());
            orderRepo.save(order);
            log.info("SAGA: FRAUD_REVIEW: orderId={}", orderId);
        }
    }

    @Transactional
    @Observed(name = "saga.order.payment-processed",
            contextualName = "payment-processed")
    public void onPaymentProcessed(String orderId, String chargeId, BigDecimal amount, String causationId) {
        Order order = findOrder(orderId);
        if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
            log.debug("payment.processed idempotent skip: orderId={}", orderId);
            return;
        }

        int nextVersion = nextVersion(orderId);
        appendEvent(orderId, "PAYMENT_COMPLETED", nextVersion,
                Map.of("charge_id", chargeId != null ? chargeId : "",
                        "amount", amount.toString(), "timestamp", Instant.now().toString()),
                causationId);

        order.setStatus(OrderStatus.PAID);
        order.setChargeId(chargeId);
        order.setLastEventVersion(nextVersion);
        order.setLastEventAt(Instant.now());
        orderRepo.save(order);

        List<OrderItem> items = itemRepo.findByOrderId(orderId);

        outboxRepo.save(OrderOutbox.builder()
                .eventType("event.order-paid")
                .payload(Map.of(
                        "event_type",  "event.order-paid",
                        "order_id",    orderId,
                        "buyer_id",    order.getBuyerId(),
                        "merchant_id", order.getMerchantId(),
                        "amount",      amount.toString(),
                        "currency",    order.getCurrency(),
                        "charge_id",   chargeId != null ? chargeId : "",
                        "product_ids", items.stream().map(OrderItem::getProductId).toList(),
                        "timestamp",   Instant.now().toString()
                ))
                .aggregateId(orderId)
                .build());

        outboxRepo.save(OrderOutbox.builder()
                .eventType("cmd.confirm-inventory")
                .payload(Map.of(
                        "order_id",        orderId,
                        "reservation_id",  order.getReservationId() != null ? order.getReservationId() : "",
                        "idempotency_key", orderId + "-confirm"
                ))
                .aggregateId(orderId)
                .build());

        log.info("SAGA: PAYMENT_COMPLETED → PAID: orderId={}, chargeId={}", orderId, chargeId);
    }

    @Transactional
    @Observed(name = "saga.order.payment-failed",
            contextualName = "payment-failed")
    public void onPaymentFailed(String orderId, String reason, String causationId) {
        Order order = findOrder(orderId);
        if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
            log.debug("payment.failed idempotent skip: orderId={}", orderId);
            return;
        }

        int nextVersion = nextVersion(orderId);
        appendEvent(orderId, "PAYMENT_FAILED", nextVersion,
                Map.of("reason", reason, "timestamp", Instant.now().toString()), causationId);

        order.setStatus(OrderStatus.PAYMENT_FAILED);
        order.setLastEventVersion(nextVersion);
        order.setLastEventAt(Instant.now());
        orderRepo.save(order);

        outboxRepo.save(OrderOutbox.builder()
                .eventType("cmd.release-inventory")
                .payload(Map.of("order_id", orderId,
                        "reservation_id", order.getReservationId() != null ? order.getReservationId() : "",
                        "reason", "PAYMENT_FAILED", "idempotency_key", orderId + "-release"))
                .aggregateId(orderId)
                .build());

        outboxRepo.save(OrderOutbox.builder()
                .eventType("notification.payment-failed")
                .payload(Map.of(
                        "event_type", "notification.payment-failed",
                        "buyer_id",   order.getBuyerId(),
                        "order_id",   orderId,
                        "reason",     reason
                ))
                .aggregateId(orderId)
                .build());

        log.warn("SAGA: PAYMENT_FAILED: orderId={}, reason={}", orderId, reason);
    }

    @Transactional
    @Observed(name = "saga.order.inventory-released",
            contextualName = "inventory-released")
    public void onInventoryReleased(String orderId, String causationId) {
        Order order = findOrder(orderId);
        if (order.getStatus() != OrderStatus.PAYMENT_FAILED
                && order.getStatus() != OrderStatus.REJECTED) return;

        int nextVersion = nextVersion(orderId);
        appendEvent(orderId, "INVENTORY_RELEASED", nextVersion,
                Map.of("timestamp", Instant.now().toString()), causationId);

        int v2 = nextVersion + 1;
        appendEvent(orderId, "ORDER_CANCELLED", v2,
                Map.of("reason", order.getStatus().name(), "timestamp", Instant.now().toString()), null);

        order.setStatus(OrderStatus.CANCELLED);
        order.setLastEventVersion(v2);
        order.setLastEventAt(Instant.now());
        orderRepo.save(order);

        outboxRepo.save(OrderOutbox.builder()
                .eventType("event.order-cancelled")
                .payload(Map.of(
                        "event_type", "event.order-cancelled",
                        "order_id",   orderId,
                        "buyer_id",   order.getBuyerId(),
                        "reason",     "PAYMENT_OR_FRAUD_FAILURE",
                        "timestamp",  Instant.now().toString()
                ))
                .aggregateId(orderId)
                .build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers — unchanged
    // ─────────────────────────────────────────────────────────────────────────

    private void preValidate(CreateOrderRequest req) {
        OrderItemRequest firstItem = req.items().get(0);

        try {
            CompletableFuture<Boolean> invFuture = clients.checkInventoryAvailable(
                    firstItem.productId(), firstItem.qty());
            Boolean available = invFuture.get(2, java.util.concurrent.TimeUnit.SECONDS);
            if (Boolean.FALSE.equals(available)) {
                throw new BusinessValidationException("Product is out of stock: " + firstItem.productId());
            }
        } catch (BusinessValidationException e) { throw e; }
        catch (Exception e) { log.warn("Inventory pre-check failed (proceeding): {}", e.getMessage()); }

        try {
            CompletableFuture<String> mf = clients.getMerchantStatus(firstItem.merchantId());
            String status = mf.get(2, java.util.concurrent.TimeUnit.SECONDS);
            if ("SUSPENDED".equals(status) || "TERMINATED".equals(status)) {
                throw new BusinessValidationException("Merchant is not active: " + firstItem.merchantId());
            }
        } catch (BusinessValidationException e) { throw e; }
        catch (Exception e) { log.warn("Merchant pre-check failed (proceeding): {}", e.getMessage()); }

        try {
            CompletableFuture<BigDecimal> pf = clients.getCurrentPrice(firstItem.productId());
            BigDecimal currentPrice = pf.get(2, java.util.concurrent.TimeUnit.SECONDS);
            if (currentPrice != null) {
                BigDecimal diff = currentPrice.subtract(firstItem.unitPrice()).abs();
                BigDecimal tolerance = currentPrice.multiply(PRICE_DRIFT_TOLERANCE);
                if (diff.compareTo(tolerance) > 0) {
                    throw new BusinessValidationException(
                            String.format("Price changed: submitted=%.2f, current=%.2f",
                                    firstItem.unitPrice(), currentPrice));
                }
            }
        } catch (BusinessValidationException e) { throw e; }
        catch (Exception e) { log.warn("Price pre-check failed (proceeding): {}", e.getMessage()); }
    }

    private OrderAggregate reconstructAggregate(String orderId) {
        return OrderAggregate.reconstruct(eventRepo.findByOrderIdOrderByVersionAsc(orderId));
    }

    private Order findOrder(String orderId) {
        return orderRepo.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
    }

    private Order validateMerchantOwnership(String orderId, String merchantId) {
        Order order = findOrder(orderId);
        if (merchantId != null && !order.getMerchantId().equals(merchantId)) {
            throw new ForbiddenException("Order not owned by merchant: " + merchantId);
        }
        return order;
    }

    private void requireStatus(Order order, OrderStatus required, String action) {
        if (order.getStatus() != required) {
            throw new ConflictException(String.format(
                    "Cannot %s: order is %s, required %s", action, order.getStatus(), required));
        }
    }

    private void appendEvent(String orderId, String eventType, int version,
                             Map<String, Object> payload, String causationId) {
        eventRepo.save(OrderEvent.builder()
                .orderId(orderId).eventType(eventType).version(version)
                .eventPayload(payload).causationId(causationId)
                .correlationId(orderId).build());
    }

    private int nextVersion(String orderId) {
        return eventRepo.getMaxVersion(orderId) + 1;
    }

    private Map<String, Object> buildCreatedPayload(CreateOrderRequest req, BigDecimal total) {
        return Map.of(
                "buyer_id",        req.buyerId(),
                "merchant_id",     req.items().get(0).merchantId(),
                "total_amount",    total.toString(),
                "currency",        req.currency(),
                "items_count",     req.items().size(),
                "idempotency_key", req.idempotencyKey(),
                "timestamp",       Instant.now().toString()
        );
    }

    private CreateOrderResponse buildCreateResponse(Order order, List<OrderItem> items) {
        List<OrderItemResponse> dtos = items.stream()
                .map(i -> new OrderItemResponse(i.getProductId(), i.getProductTitle(),
                        i.getQty(), i.getUnitPrice(), i.subtotal()))
                .toList();
        return new CreateOrderResponse(order.getId(), order.getStatus().name(),
                order.getTotalAmount(), order.getCurrency(), dtos, order.getCreatedAt(),
                "async — poll GET /orders/" + order.getId() + " for updates");
    }

    private Map<String, Object> shippingAddressToMap(ShippingAddressDto dto) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("street",      dto.street());
        m.put("city",        dto.city());
        m.put("state",       dto.state());
        m.put("postal_code", dto.postalCode());
        m.put("country",     dto.country());
        return m;
    }

    private ShippingAddressDto mapToShippingDto(Map<String, Object> m) {
        if (m == null) return null;
        return new ShippingAddressDto(
                (String) m.getOrDefault("street", ""),
                (String) m.getOrDefault("city", ""),
                (String) m.getOrDefault("state", ""),
                (String) m.getOrDefault("postal_code", ""),
                (String) m.getOrDefault("country", ""));
    }
}