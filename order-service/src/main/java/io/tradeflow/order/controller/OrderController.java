package io.tradeflow.order.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tradeflow.order.dto.OrderDtos.*;
import io.tradeflow.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Tag(name = "Orders", description = "SAGA-orchestrated order lifecycle")
public class OrderController {

    private final OrderService orderService;

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 1 — POST /orders
    // Idempotent. Pre-validates → SAGA birth → returns CREATED immediately.
    // The SAGA continues asynchronously over Kafka.
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('BUYER') or hasRole('ADMIN')")
    @Operation(summary = "Create order and begin SAGA orchestration",
               security = @SecurityRequirement(name = "bearerAuth"))
    public CreateOrderResponse createOrder(
            @Valid @RequestBody CreateOrderRequest request) {
        return orderService.createOrder(request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 2 — GET /orders/{id}
    // Snapshot + event timeline. Buyers poll this after POST /orders.
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/orders/{orderId}")
    @PreAuthorize("hasAnyRole('BUYER', 'MERCHANT', 'ADMIN')")
    @Operation(summary = "Get order detail with SAGA timeline",
               security = @SecurityRequirement(name = "bearerAuth"))
    public OrderDetailResponse getOrder(@PathVariable String orderId) {
        return orderService.getOrder(orderId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 3 — GET /orders/{id}/history
    // Complete append-only audit trail. Compliance / dispute resolution.
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/orders/{orderId}/history")
    @PreAuthorize("hasAnyRole('BUYER', 'MERCHANT', 'ADMIN', 'COMPLIANCE')")
    @Operation(summary = "Full event sourcing audit trail",
               security = @SecurityRequirement(name = "bearerAuth"))
    public OrderHistoryResponse getOrderHistory(@PathVariable String orderId) {
        return orderService.getOrderHistory(orderId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 4 — GET /buyers/{buyerId}/orders
    // Paginated order history. "My Orders" screen.
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/buyers/{buyerId}/orders")
    @PreAuthorize("hasAnyRole('BUYER', 'ADMIN')")
    @Operation(summary = "Buyer order history (paginated)",
               security = @SecurityRequirement(name = "bearerAuth"))
    public BuyerOrdersResponse getBuyerOrders(
            @PathVariable String buyerId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal Jwt jwt) {
        // Authorization: buyers can only see their own orders
        String sub = jwt.getSubject();
        boolean isAdmin = jwt.getClaimAsStringList("roles") != null
                && jwt.getClaimAsStringList("roles").contains("ADMIN");
        if (!isAdmin && !sub.equals(buyerId)) {
            throw new io.tradeflow.order.service.ForbiddenException("Cannot view other buyer's orders");
        }
        return orderService.getBuyerOrders(buyerId, status, page, Math.min(size, 50));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 5 — POST /orders/{id}/cancel
    // Only from CREATED state. No compensation needed — nothing engaged yet.
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/orders/{orderId}/cancel")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('BUYER', 'ADMIN')")
    @Operation(summary = "Cancel order (only from CREATED state)",
               security = @SecurityRequirement(name = "bearerAuth"))
    public void cancelOrder(
            @PathVariable String orderId,
            @Valid @RequestBody CancelOrderRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        orderService.cancelOrder(orderId, request, jwt.getSubject());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 6 — POST /orders/{id}/fulfillment/acknowledge
    // Merchant confirms receipt of order: PAID → FULFILLING
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/orders/{orderId}/fulfillment/acknowledge")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('MERCHANT', 'ADMIN')")
    @Operation(summary = "Merchant acknowledges order → FULFILLING",
               security = @SecurityRequirement(name = "bearerAuth"))
    public void acknowledgeFulfillment(
            @PathVariable String orderId,
            @Valid @RequestBody AcknowledgeRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String merchantId = jwt.getClaimAsString("merchant_id");
        orderService.acknowledgeFulfillment(orderId, request, merchantId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 7 — POST /orders/{id}/shipment
    // Merchant marks as shipped: FULFILLING → SHIPPED
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/orders/{orderId}/shipment")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('MERCHANT', 'ADMIN')")
    @Operation(summary = "Mark order as shipped",
               security = @SecurityRequirement(name = "bearerAuth"))
    public void markShipped(
            @PathVariable String orderId,
            @Valid @RequestBody ShipmentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String merchantId = jwt.getClaimAsString("merchant_id");
        orderService.markShipped(orderId, request, merchantId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 8 — POST /orders/{id}/delivery
    // Courier webhook: SHIPPED → DELIVERED + triggers merchant payout release
    // Secured by HMAC signature validation in filter (not JWT)
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/orders/{orderId}/delivery")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('SERVICE', 'ADMIN')")
    @Operation(summary = "Courier delivery confirmation webhook")
    public void markDelivered(
            @PathVariable String orderId,
            @Valid @RequestBody DeliveryRequest request) {
        orderService.markDelivered(orderId, request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 9 — POST /orders/{id}/return
    // Buyer requests return: DELIVERED → RETURN_REQUESTED
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/orders/{orderId}/return")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('BUYER', 'ADMIN')")
    @Operation(summary = "Request order return",
               security = @SecurityRequirement(name = "bearerAuth"))
    public void requestReturn(
            @PathVariable String orderId,
            @Valid @RequestBody ReturnRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        orderService.requestReturn(orderId, request, jwt.getSubject());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 10 — GET /internal/orders/{id}/status
    // Fast-path for SAGA participants (Payment, Fraud). No JWT — NetworkPolicy only.
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/internal/orders/{orderId}/status")
    @Operation(summary = "Internal SAGA participant status check")
    public InternalOrderStatusResponse getInternalStatus(@PathVariable String orderId) {
        return orderService.getInternalStatus(orderId);
    }
}
