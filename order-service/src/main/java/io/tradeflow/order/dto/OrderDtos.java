package io.tradeflow.order.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class OrderDtos {

    private OrderDtos() {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 1 — POST /orders
    // ─────────────────────────────────────────────────────────────────────────

    public record OrderItemRequest(
            @NotBlank String productId,
            @NotBlank String merchantId,
            @NotNull @Min(1) Integer qty,
            @NotNull @DecimalMin("0.01") BigDecimal unitPrice
    ) {}

    public record ShippingAddressDto(
            @NotBlank String street,
            @NotBlank String city,
            @NotBlank String state,
            @NotBlank String postalCode,
            @NotBlank String country
    ) {}

    public record CreateOrderRequest(
            @NotBlank String buyerId,
            @Valid @NotEmpty List<OrderItemRequest> items,
            @Valid @NotNull ShippingAddressDto shippingAddress,
            @NotBlank String paymentMethodToken,
            @NotBlank String currency,
            @NotBlank String idempotencyKey
    ) {}

    public record OrderItemResponse(
            String productId,
            String title,
            int qty,
            BigDecimal unitPrice,
            BigDecimal subtotal
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateOrderResponse(
            String orderId,
            String status,
            BigDecimal totalAmount,
            String currency,
            List<OrderItemResponse> items,
            Instant createdAt,
            String estimatedCompletion
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 2 — GET /orders/{id}
    // ─────────────────────────────────────────────────────────────────────────

    public record SagaTimelineEntry(String status, String at) {}

    public record CurrentStateDto(String status, String since, int eventVersion) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrderDetailResponse(
            String orderId,
            String status,
            String buyerId,
            String merchantId,
            BigDecimal totalAmount,
            String currency,
            List<OrderItemResponse> items,
            ShippingAddressDto shippingAddress,
            CurrentStateDto currentState,
            List<SagaTimelineEntry> sagaTimeline,
            Instant createdAt,
            Instant deliveredAt
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 3 — GET /orders/{id}/history
    // ─────────────────────────────────────────────────────────────────────────

    public record OrderEventDto(
            int version,
            String eventType,
            String occurredAt,
            String causationId,
            Map<String, Object> payload
    ) {}

    public record OrderHistoryResponse(
            String orderId,
            int totalEvents,
            List<OrderEventDto> events
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 4 — GET /buyers/{buyerId}/orders
    // ─────────────────────────────────────────────────────────────────────────

    public record OrderSummaryDto(
            String orderId,
            String status,
            BigDecimal totalAmount,
            String currency,
            String itemsSummary,
            Instant createdAt,
            Instant deliveredAt
    ) {}

    public record BuyerOrdersResponse(
            String buyerId,
            long totalOrders,
            int page,
            int pageSize,
            List<OrderSummaryDto> orders
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 5 — POST /orders/{id}/cancel
    // ─────────────────────────────────────────────────────────────────────────

    public record CancelOrderRequest(
            @NotBlank String reason,
            String buyerNote
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 6 — POST /orders/{id}/fulfillment/acknowledge
    // ─────────────────────────────────────────────────────────────────────────

    public record AcknowledgeRequest(
            @NotBlank String merchantUserId,
            LocalDate estimatedShipDate,
            String fulfillmentNotes
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 7 — POST /orders/{id}/shipment
    // ─────────────────────────────────────────────────────────────────────────

    public record ShipmentRequest(
            @NotBlank String courier,
            @NotBlank String trackingNumber,
            String trackingUrl,
            Instant shippedAt,
            LocalDate estimatedDelivery
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 8 — POST /orders/{id}/delivery
    // ─────────────────────────────────────────────────────────────────────────

    public record DeliveryRequest(
            @NotBlank String trackingNumber,
            @NotNull Instant deliveredAt,
            String deliveryConfirmation,
            String recipientName,
            String proofOfDeliveryUrl
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 9 — POST /orders/{id}/return
    // ─────────────────────────────────────────────────────────────────────────

    public record ReturnRequest(
            @NotBlank String reason,
            String description,
            List<String> evidenceUrls,
            String returnPreference
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 10 — GET /internal/orders/{id}/status
    // ─────────────────────────────────────────────────────────────────────────

    public record InternalOrderStatusResponse(
            String orderId,
            String status,
            String buyerId,
            BigDecimal totalAmount,
            int lastEventVersion
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ERRORS
    // ─────────────────────────────────────────────────────────────────────────

    public record ErrorResponse(String error, String message, int status, Instant timestamp) {
        public static ErrorResponse of(String error, String message, int status) {
            return new ErrorResponse(error, message, status, Instant.now());
        }
    }
}
