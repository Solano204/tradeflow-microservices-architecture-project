package io.tradeflow.inventory.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tradeflow.inventory.dto.InventoryDtos.*;
import io.tradeflow.inventory.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Tag(name = "Inventory", description = "Stock levels, two-phase reserve/confirm/release")
public class InventoryController {

    private final InventoryService inventoryService;

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 1 — POST /inventory
    // Called internally when product.created Kafka event fires.
    // Also callable by admin/internal services directly.
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/inventory")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'SERVICE')")
    @Operation(summary = "Initialize inventory for a product", security = @SecurityRequirement(name = "bearerAuth"))
    public CreateInventoryResponse createInventory(@Valid @RequestBody CreateInventoryRequest request) {
        return inventoryService.createInventory(request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 2 — GET /inventory/{productId}
    // Full detail with live available_qty computation and active reservations.
    // No cache — merchants need real-time accuracy.
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/inventory/{productId}")
    @PreAuthorize("hasAnyRole('MERCHANT', 'ADMIN')")
    @Operation(summary = "Get inventory detail (live computation)", security = @SecurityRequirement(name = "bearerAuth"))
    public InventoryDetailResponse getInventory(@PathVariable String productId) {
        return inventoryService.getInventory(productId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 3 — PUT /inventory/{productId}/stock
    // Merchant adds stock. @Version OL prevents concurrent restock+reservation conflict.
    // ─────────────────────────────────────────────────────────────────────────

    @PutMapping("/inventory/{productId}/stock")
    @PreAuthorize("hasAnyRole('MERCHANT', 'ADMIN')")
    @Operation(summary = "Add stock (restock)", security = @SecurityRequirement(name = "bearerAuth"))
    public RestockResponse restock(
            @PathVariable String productId,
            @Valid @RequestBody RestockRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String merchantId = jwt.getClaimAsString("merchant_id");
        return inventoryService.restock(productId, request, merchantId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 4 — POST /inventory/{productId}/reserve
    // Phase 1 of two-phase protocol. Called by Order Service (service-to-service).
    // @Version optimistic locking + Resilience4j retry handles concurrent buyers.
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/inventory/{productId}/reserve")
    @PreAuthorize("hasAnyRole('SERVICE', 'ADMIN')")
    @Operation(summary = "Reserve stock for checkout (Phase 1)", security = @SecurityRequirement(name = "bearerAuth"))
    public ReserveResponse reserve(
            @PathVariable String productId,
            @Valid @RequestBody ReserveRequest request) {
        return inventoryService.reserve(productId, request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 5 — POST /inventory/{productId}/confirm
    // Phase 2a — payment succeeded. Permanently decrements total_qty.
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/inventory/{productId}/confirm")
    @PreAuthorize("hasAnyRole('SERVICE', 'ADMIN')")
    @Operation(summary = "Confirm reservation after payment (Phase 2a)", security = @SecurityRequirement(name = "bearerAuth"))
    @ResponseStatus(HttpStatus.OK)
    public void confirm(
            @PathVariable String productId,
            @Valid @RequestBody ConfirmRequest request) {
        inventoryService.confirm(productId, request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 6 — POST /inventory/{productId}/release
    // Phase 2b — compensation path. available_qty restores automatically.
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/inventory/{productId}/release")
    @PreAuthorize("hasAnyRole('SERVICE', 'ADMIN')")
    @Operation(summary = "Release reservation (Phase 2b — compensation)", security = @SecurityRequirement(name = "bearerAuth"))
    @ResponseStatus(HttpStatus.OK)
    public void release(
            @PathVariable String productId,
            @Valid @RequestBody ReleaseRequest request) {
        inventoryService.release(productId, request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 7 — PUT /inventory/{productId}/threshold
    // Configure low-stock alert threshold. Fires immediately if already breached.
    // ─────────────────────────────────────────────────────────────────────────

    @PutMapping("/inventory/{productId}/threshold")
    @PreAuthorize("hasAnyRole('MERCHANT', 'ADMIN')")
    @Operation(summary = "Set low-stock alert threshold", security = @SecurityRequirement(name = "bearerAuth"))
    @ResponseStatus(HttpStatus.OK)
    public void updateThreshold(
            @PathVariable String productId,
            @Valid @RequestBody ThresholdRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String merchantId = jwt.getClaimAsString("merchant_id");
        inventoryService.updateThreshold(productId, request, merchantId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 8 — GET /internal/inventory/{productId}/available
    // Internal fast-path pre-check for Order Service SAGA initiation.
    // Returns a hint — not a lock. Actual guarantee is in the reserve endpoint.
    // Secured by K8s NetworkPolicy (only order-service pods can reach /internal/*).
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/internal/inventory/{productId}/available")
    @Operation(summary = "Internal availability check (Order Service fast-path)")
    public AvailabilityResponse checkAvailability(
            @PathVariable String productId,
            @RequestParam(defaultValue = "1") int qty) {
        return inventoryService.checkAvailability(productId, qty);
    }
}
