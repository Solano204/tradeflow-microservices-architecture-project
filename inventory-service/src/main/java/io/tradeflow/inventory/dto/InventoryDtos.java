package io.tradeflow.inventory.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

public final class InventoryDtos {

    private InventoryDtos() {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 1 — POST /inventory
    // ─────────────────────────────────────────────────────────────────────────

    public record CreateInventoryRequest(
            @NotBlank String productId,
            @NotBlank String merchantId,
            String sku,
            @NotNull @Min(0) Integer initialQty,
            @Min(1) Integer lowStockThreshold,
            @Min(1) Integer reservationTtlMinutes
    ) {}

    public record CreateInventoryResponse(
            String inventoryId,
            String productId,
            int totalQty,
            int availableQty,
            int reservedQty,
            String status,
            Instant createdAt
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 2 — GET /inventory/{productId}
    // ─────────────────────────────────────────────────────────────────────────

    public record ActiveReservationDto(
            String orderId,
            int qty,
            Instant expiresAt,
            String status
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InventoryDetailResponse(
            String inventoryId,
            String productId,
            String sku,
            int totalQty,
            int availableQty,
            int reservedQty,
            String status,
            int lowStockThreshold,
            List<ActiveReservationDto> reservationsActive
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 3 — PUT /inventory/{productId}/stock
    // ─────────────────────────────────────────────────────────────────────────

    public record RestockRequest(
            @NotNull @Min(1) Integer qtyToAdd,
            @NotBlank String reason,
            String notes,
            String purchaseOrderRef
    ) {}

    public record RestockResponse(
            String inventoryId,
            int previousTotalQty,
            int addedQty,
            int newTotalQty,
            int newAvailableQty,
            String status
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 4 — POST /inventory/{productId}/reserve
    // ─────────────────────────────────────────────────────────────────────────

    public record ReserveRequest(
            @NotBlank String orderId,
            @NotBlank String buyerId,
            @NotNull @Min(1) Integer qty,
            @NotBlank String idempotencyKey
    ) {}

    public record ReserveResponse(
            String reservationId,
            String productId,
            String orderId,
            int qtyReserved,
            Instant expiresAt,
            int availableQtyAfter,
            String status
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 5 — POST /inventory/{productId}/confirm
    // ─────────────────────────────────────────────────────────────────────────

    public record ConfirmRequest(
            @NotBlank String reservationId,
            @NotBlank String orderId,
            @NotBlank String idempotencyKey
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 6 — POST /inventory/{productId}/release
    // ─────────────────────────────────────────────────────────────────────────

    public record ReleaseRequest(
            @NotBlank String reservationId,
            @NotBlank String orderId,
            @NotBlank String reason,
            @NotBlank String idempotencyKey
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 7 — PUT /inventory/{productId}/threshold
    // ─────────────────────────────────────────────────────────────────────────

    public record ThresholdRequest(
            @NotNull @Min(1) Integer lowStockThreshold,
            List<String> notificationChannels
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 8 — GET /internal/inventory/{productId}/available
    // ─────────────────────────────────────────────────────────────────────────

    public record AvailabilityResponse(
            String productId,
            int availableQty,
            int requestedQty,
            boolean isAvailable,
            String status
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
