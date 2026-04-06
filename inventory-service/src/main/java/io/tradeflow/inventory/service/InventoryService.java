package io.tradeflow.inventory.service;

import io.github.resilience4j.retry.annotation.Retry;
import io.tradeflow.inventory.dto.InventoryDtos.*;
import io.tradeflow.inventory.entity.*;
import io.tradeflow.inventory.entity.Inventory.InventoryStatus;
import io.tradeflow.inventory.entity.StockReservation.ReservationStatus;
import io.tradeflow.inventory.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * InventoryService
 *
 * CRITICAL FIX — event_type was missing from every Kafka payload.
 *
 * The outbox entity has TWO event_type fields:
 *   1. InventoryOutbox.eventType  → DB column, used by OutboxRelayService to route to topic
 *   2. payload.get("event_type")  → the JSON field inside the Kafka message body
 *
 * Consumers (Notification, Search, Order, Analytics) read event_type from the
 * payload body, NOT from a Kafka header. Without it in the payload, every consumer
 * logs "ignoring eventType='null'" and discards the message.
 *
 * Fix: add "event_type" as the FIRST key in every payload Map.
 * This makes it consistent with Payment Service's MockPaymentService which
 * already includes event_type correctly in its payloads.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final InventoryRepository inventoryRepo;
    private final StockReservationRepository reservationRepo;
    private final InventoryOutboxRepository outboxRepo;

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 1 — POST /inventory
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public CreateInventoryResponse createInventory(CreateInventoryRequest req) {
        if (inventoryRepo.findByProductId(req.productId()).isPresent()) {
            throw new ConflictException("Inventory already exists for product: " + req.productId());
        }

        String inventoryId = "inv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        Inventory inventory = Inventory.builder()
                .id(inventoryId)
                .productId(req.productId())
                .merchantId(req.merchantId())
                .sku(req.sku())
                .totalQty(req.initialQty())
                .lowStockThreshold(req.lowStockThreshold() != null ? req.lowStockThreshold() : 10)
                .reservationTtlMinutes(req.reservationTtlMinutes() != null ? req.reservationTtlMinutes() : 10)
                .status(req.initialQty() > 0 ? InventoryStatus.IN_STOCK : InventoryStatus.OUT_OF_STOCK)
                .build();

        inventoryRepo.save(inventory);

        // FIX: include event_type in payload body so consumers can route correctly
        outboxRepo.save(InventoryOutbox.builder()
                .eventType("inventory.initialized")
                .payload(Map.of(
                        "event_type",    "inventory.initialized",   // ← ADDED
                        "inventory_id",  inventoryId,
                        "product_id",    req.productId(),
                        "merchant_id",   req.merchantId(),
                        "initial_qty",   req.initialQty(),
                        "timestamp",     Instant.now().toString()
                ))
                .aggregateId(inventoryId)
                .build());

        log.info("Inventory created: inventoryId={}, productId={}, qty={}",
                inventoryId, req.productId(), req.initialQty());

        return new CreateInventoryResponse(
                inventoryId, req.productId(),
                req.initialQty(), req.initialQty(), 0,
                inventory.getStatus().name(), inventory.getCreatedAt());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 2 — GET /inventory/{productId}
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public InventoryDetailResponse getInventory(String productId) {
        Inventory inventory = findByProductId(productId);
        Instant now = Instant.now();

        int reservedQty  = inventoryRepo.computeReservedQty(inventory.getId(), now);
        int availableQty = inventory.getTotalQty() - reservedQty;

        List<StockReservation> activeReservations =
                reservationRepo.findActiveByInventoryId(inventory.getId(), now);

        List<ActiveReservationDto> reservationDtos = activeReservations.stream()
                .map(r -> new ActiveReservationDto(r.getOrderId(), r.getQty(),
                        r.getExpiresAt(), r.getStatus().name()))
                .toList();

        return new InventoryDetailResponse(
                inventory.getId(), productId, inventory.getSku(),
                inventory.getTotalQty(), availableQty, reservedQty,
                inventory.getStatus().name(), inventory.getLowStockThreshold(),
                reservationDtos);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 3 — PUT /inventory/{productId}/stock (restock)
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    @Retry(name = "inventory-write", fallbackMethod = "restockFallback")
    public RestockResponse restock(String productId, RestockRequest req, String merchantId) {
        Inventory inventory = findOwnedInventory(productId, merchantId);
        int previousTotal = inventory.getTotalQty();

        inventory.setTotalQty(previousTotal + req.qtyToAdd());

        Instant now = Instant.now();
        int reservedQty  = inventoryRepo.computeReservedQty(inventory.getId(), now);
        int newAvailable = inventory.getTotalQty() - reservedQty;

        InventoryStatus previousStatus = inventory.getStatus();
        inventory.updateStatusFromAvailable(newAvailable);
        inventoryRepo.save(inventory);

        Map<String, Object> outboxPayload = new LinkedHashMap<>();
        outboxPayload.put("event_type",      "inventory.restocked");   // ← ADDED
        outboxPayload.put("inventory_id",    inventory.getId());
        outboxPayload.put("product_id",      productId);
        outboxPayload.put("merchant_id",     merchantId);
        outboxPayload.put("qty_added",       req.qtyToAdd());
        outboxPayload.put("new_total_qty",   inventory.getTotalQty());
        outboxPayload.put("new_available_qty", newAvailable);
        outboxPayload.put("reason",          req.reason());
        if (req.purchaseOrderRef() != null)
            outboxPayload.put("po_ref", req.purchaseOrderRef());
        outboxPayload.put("timestamp",       now.toString());

        outboxRepo.save(InventoryOutbox.builder()
                .eventType("inventory.restocked")
                .payload(outboxPayload)
                .aggregateId(inventory.getId())
                .build());

        if (previousStatus == InventoryStatus.OUT_OF_STOCK && newAvailable > 0) {
            log.info("Product back in stock: productId={}", productId);
        }

        log.info("Restocked: productId={}, +{}units, newTotal={}",
                productId, req.qtyToAdd(), inventory.getTotalQty());

        return new RestockResponse(
                inventory.getId(), previousTotal, req.qtyToAdd(),
                inventory.getTotalQty(), newAvailable, inventory.getStatus().name());
    }

    @SuppressWarnings("unused")
    public RestockResponse restockFallback(String productId, RestockRequest req,
                                           String merchantId, Throwable t) {
        log.error("Restock failed after retries: productId={}, error={}", productId, t.getMessage());
        throw new ConflictException("Restock failed due to concurrent conflict — please retry");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 4 — POST /inventory/{productId}/reserve (Phase 1)
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    @Retry(name = "inventory-reserve", fallbackMethod = "reserveFallback")
    public ReserveResponse reserve(String productId, ReserveRequest req) {
        Inventory inventory = findByProductId(productId);
        Instant now = Instant.now();

        // IDEMPOTENCY
        Optional<StockReservation> existing =
                reservationRepo.findByIdempotencyKey(req.idempotencyKey());
        if (existing.isPresent()) {
            StockReservation r = existing.get();
            log.debug("Reserve idempotent hit: orderId={}, reservationId={}",
                    req.orderId(), r.getId());
            int reservedQty = inventoryRepo.computeReservedQty(inventory.getId(), now);
            return new ReserveResponse(r.getId(), productId, req.orderId(),
                    r.getQty(), r.getExpiresAt(),
                    inventory.getTotalQty() - reservedQty, r.getStatus().name());
        }

        // AVAILABILITY CHECK
        int reservedQty  = inventoryRepo.computeReservedQty(inventory.getId(), now);
        int availableQty = inventory.getTotalQty() - reservedQty;

        if (availableQty < req.qty()) {
            log.info("Insufficient stock: productId={}, available={}, requested={}",
                    productId, availableQty, req.qty());
            throw new InsufficientStockException(
                    String.format("Insufficient stock: available=%d, requested=%d",
                            availableQty, req.qty()), availableQty);
        }

        String reservationId = "res_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Instant expiresAt = now.plusSeconds((long) inventory.getReservationTtlMinutes() * 60);

        StockReservation reservation = StockReservation.builder()
                .id(reservationId)
                .inventoryId(inventory.getId())
                .orderId(req.orderId())
                .buyerId(req.buyerId())
                .qty(req.qty())
                .status(ReservationStatus.PENDING)
                .idempotencyKey(req.idempotencyKey())
                .expiresAt(expiresAt)
                .build();

        reservationRepo.save(reservation);

        inventory.setUpdatedAt(Instant.now());
        inventoryRepo.save(inventory); // @Version increment

        int availableAfter = availableQty - req.qty();

        // FIX: event_type in payload body
        outboxRepo.save(InventoryOutbox.builder()
                .eventType("inventory.reserved")
                .payload(Map.of(
                        "event_type",     "inventory.reserved",    // ← ADDED
                        "reservation_id", reservationId,
                        "inventory_id",   inventory.getId(),
                        "order_id",       req.orderId(),
                        "product_id",     productId,
                        "merchant_id",    inventory.getMerchantId(),
                        "qty",            req.qty(),
                        "expires_at",     expiresAt.toString(),
                        "timestamp",      now.toString()
                ))
                .aggregateId(inventory.getId())
                .build());

        log.info("Reserved: reservationId={}, productId={}, orderId={}, qty={}",
                reservationId, productId, req.orderId(), req.qty());

        return new ReserveResponse(reservationId, productId, req.orderId(),
                req.qty(), expiresAt, availableAfter, "PENDING");
    }

    @SuppressWarnings("unused")
    public ReserveResponse reserveFallback(String productId, ReserveRequest req, Throwable t) {
        if (t instanceof InsufficientStockException ise) throw ise;
        log.error("Reserve failed after retries: productId={}, error={}", productId, t.getMessage());
        throw new ConflictException("Concurrent conflict reserving stock — please retry");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 5 — POST /inventory/{productId}/confirm (Phase 2a)
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    @Retry(name = "inventory-write", fallbackMethod = "confirmFallback")
    public void confirm(String productId, ConfirmRequest req) {
        StockReservation reservation =
                reservationRepo.findByIdAndOrderId(req.reservationId(), req.orderId())
                        .orElseThrow(() -> new NotFoundException(
                                "Reservation not found: " + req.reservationId()));

        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            log.debug("Confirm idempotent: reservationId={}", req.reservationId());
            return;
        }

        if (reservation.getStatus() == ReservationStatus.EXPIRED
                || reservation.getExpiresAt().isBefore(Instant.now())) {
            throw new ReservationExpiredException(
                    "Reservation expired before payment completed: " + req.reservationId());
        }

        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new BadRequestException(
                    "Cannot confirm reservation in status: " + reservation.getStatus());
        }

        Inventory inventory = inventoryRepo.findById(reservation.getInventoryId())
                .orElseThrow(() -> new NotFoundException("Inventory not found for reservation"));

        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setResolvedAt(Instant.now());
        reservationRepo.save(reservation);

        inventory.setTotalQty(inventory.getTotalQty() - reservation.getQty());
        inventoryRepo.save(inventory);

        int reservedQty  = inventoryRepo.computeReservedQty(inventory.getId(), Instant.now());
        int newAvailable = inventory.getTotalQty() - reservedQty;

        InventoryStatus previousStatus = inventory.getStatus();
        inventory.updateStatusFromAvailable(newAvailable);
        inventoryRepo.save(inventory);

        boolean crossedLowStock = previousStatus == InventoryStatus.IN_STOCK
                && inventory.getStatus() == InventoryStatus.LOW_STOCK;
        boolean justSoldOut = inventory.getStatus() == InventoryStatus.OUT_OF_STOCK;

        if (crossedLowStock || justSoldOut) {
            String alertType = justSoldOut ? "inventory.out-of-stock" : "inventory.low-stock";
            outboxRepo.save(InventoryOutbox.builder()
                    .eventType(alertType)
                    .payload(Map.of(
                            "event_type",   alertType,              // ← ADDED
                            "inventory_id", inventory.getId(),
                            "product_id",   productId,
                            "merchant_id",  inventory.getMerchantId(),
                            "available_qty", newAvailable,
                            "threshold",    inventory.getLowStockThreshold(),
                            "current_stock", newAvailable,
                            "timestamp",    Instant.now().toString()
                    ))
                    .aggregateId(inventory.getId())
                    .build());

            log.warn("Stock threshold crossed: productId={}, available={}, status={}",
                    productId, newAvailable, inventory.getStatus());
        }

        outboxRepo.save(InventoryOutbox.builder()
                .eventType("inventory.confirmed")
                .payload(Map.of(
                        "event_type",     "inventory.confirmed",   // ← ADDED
                        "reservation_id", req.reservationId(),
                        "order_id",       req.orderId(),
                        "product_id",     productId,
                        "qty_sold",       reservation.getQty(),
                        "new_total_qty",  inventory.getTotalQty(),
                        "timestamp",      Instant.now().toString()
                ))
                .aggregateId(inventory.getId())
                .build());

        log.info("Confirmed: reservationId={}, productId={}, qtySold={}",
                req.reservationId(), productId, reservation.getQty());
    }

    @SuppressWarnings("unused")
    public void confirmFallback(String productId, ConfirmRequest req, Throwable t) {
        if (t instanceof ReservationExpiredException || t instanceof NotFoundException
                || t instanceof BadRequestException) throw (RuntimeException) t;
        log.error("Confirm failed after retries: reservationId={}", req.reservationId());
        throw new ConflictException("Concurrent conflict confirming reservation — please retry");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 6 — POST /inventory/{productId}/release (Phase 2b)
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void release(String productId, ReleaseRequest req) {
        StockReservation reservation =
                reservationRepo.findByIdAndOrderId(req.reservationId(), req.orderId())
                        .orElseGet(() -> {
                            log.debug("Release: reservation not found (already cleaned up): {}",
                                    req.reservationId());
                            return null;
                        });

        if (reservation == null) return;

        if (reservation.getStatus() == ReservationStatus.RELEASED
                || reservation.getStatus() == ReservationStatus.EXPIRED) {
            log.debug("Release idempotent: reservationId={}, status={}",
                    req.reservationId(), reservation.getStatus());
            return;
        }

        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new BadRequestException(
                    "Cannot release reservation in status: " + reservation.getStatus());
        }

        reservation.setStatus(ReservationStatus.RELEASED);
        reservation.setResolvedAt(Instant.now());
        reservation.setReleaseReason(req.reason());
        reservationRepo.save(reservation);

        Inventory inventory = inventoryRepo.findById(reservation.getInventoryId())
                .orElseThrow(() -> new NotFoundException("Inventory not found"));

        int reservedQty  = inventoryRepo.computeReservedQty(inventory.getId(), Instant.now());
        int newAvailable = inventory.getTotalQty() - reservedQty;

        if (inventory.wasOutOfStock() && newAvailable > 0) {
            inventory.updateStatusFromAvailable(newAvailable);
            inventoryRepo.save(inventory);

            outboxRepo.save(InventoryOutbox.builder()
                    .eventType("inventory.restocked")
                    .payload(Map.of(
                            "event_type",   "inventory.restocked",  // ← ADDED
                            "inventory_id", inventory.getId(),
                            "product_id",   productId,
                            "merchant_id",  inventory.getMerchantId(),
                            "reason",       "RESERVATION_RELEASED",
                            "available_qty", newAvailable,
                            "timestamp",    Instant.now().toString()
                    ))
                    .aggregateId(inventory.getId())
                    .build());
        }

        outboxRepo.save(InventoryOutbox.builder()
                .eventType("inventory.released")
                .payload(Map.of(
                        "event_type",     "inventory.released",    // ← ADDED
                        "reservation_id", req.reservationId(),
                        "order_id",       req.orderId(),
                        "product_id",     productId,
                        "merchant_id",    inventory.getMerchantId(),
                        "qty_released",   reservation.getQty(),
                        "reason",         req.reason(),
                        "timestamp",      Instant.now().toString()
                ))
                .aggregateId(inventory.getId())
                .build());

        log.info("Released: reservationId={}, productId={}, qty={}, reason={}",
                req.reservationId(), productId, reservation.getQty(), req.reason());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 7 — PUT /inventory/{productId}/threshold
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void updateThreshold(String productId, ThresholdRequest req, String merchantId) {
        Inventory inventory = findOwnedInventory(productId, merchantId);
        inventory.setLowStockThreshold(req.lowStockThreshold());
        inventoryRepo.save(inventory);

        int reservedQty  = inventoryRepo.computeReservedQty(inventory.getId(), Instant.now());
        int availableQty = inventory.getTotalQty() - reservedQty;

        if (availableQty <= req.lowStockThreshold()
                && inventory.getStatus() != InventoryStatus.LOW_STOCK) {
            inventory.setStatus(InventoryStatus.LOW_STOCK);
            inventoryRepo.save(inventory);

            outboxRepo.save(InventoryOutbox.builder()
                    .eventType("inventory.low-stock")
                    .payload(Map.of(
                            "event_type",   "inventory.low-stock",  // ← ADDED
                            "inventory_id", inventory.getId(),
                            "product_id",   productId,
                            "merchant_id",  merchantId,
                            "available_qty", availableQty,
                            "current_stock", availableQty,
                            "threshold",    req.lowStockThreshold(),
                            "reason",       "THRESHOLD_SET_BELOW_CURRENT_STOCK",
                            "timestamp",    Instant.now().toString()
                    ))
                    .aggregateId(inventory.getId())
                    .build());

            log.info("Low-stock alert triggered on threshold change: productId={}, available={}, threshold={}",
                    productId, availableQty, req.lowStockThreshold());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 8 — GET /internal/inventory/{productId}/available
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AvailabilityResponse checkAvailability(String productId, int requestedQty) {
        Inventory inventory = findByProductId(productId);
        int reservedQty  = inventoryRepo.computeReservedQty(inventory.getId(), Instant.now());
        int availableQty = inventory.getTotalQty() - reservedQty;

        return new AvailabilityResponse(productId, availableQty, requestedQty,
                availableQty >= requestedQty, inventory.getStatus().name());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TTL CLEANUP JOB
    // ─────────────────────────────────────────────────────────────────────────

    @Scheduled(fixedRate = 300_000)
    @SchedulerLock(name = "inventory-cleanup", lockAtLeastFor = "PT4M", lockAtMostFor = "PT4M50S")
    @Transactional
    public void cleanExpiredReservations() {
        Instant now = Instant.now();
        List<StockReservation> expired = reservationRepo.findExpiredWithLock(now, 500);
        if (expired.isEmpty()) return;

        int totalQtyRestored = 0;
        List<String> restockedProducts = new ArrayList<>();

        for (StockReservation r : expired) {
            r.setStatus(ReservationStatus.EXPIRED);
            r.setResolvedAt(now);
            reservationRepo.save(r);
            totalQtyRestored += r.getQty();

            Inventory inventory = inventoryRepo.findById(r.getInventoryId()).orElse(null);
            if (inventory != null && inventory.wasOutOfStock()) {
                int reservedAfter  = inventoryRepo.computeReservedQty(inventory.getId(), now);
                int availableAfter = inventory.getTotalQty() - reservedAfter;
                if (availableAfter > 0) {
                    inventory.updateStatusFromAvailable(availableAfter);
                    inventoryRepo.save(inventory);
                    restockedProducts.add(inventory.getProductId());

                    outboxRepo.save(InventoryOutbox.builder()
                            .eventType("inventory.restocked")
                            .payload(Map.of(
                                    "event_type",   "inventory.restocked",  // ← ADDED
                                    "inventory_id", inventory.getId(),
                                    "product_id",   inventory.getProductId(),
                                    "merchant_id",  inventory.getMerchantId(),
                                    "reason",       "RESERVATION_EXPIRED",
                                    "available_qty", availableAfter,
                                    "timestamp",    now.toString()
                            ))
                            .aggregateId(inventory.getId())
                            .build());
                }
            }
        }

        log.info("TTL cleanup: expired {} reservations, restored {} units, {} products back in stock",
                expired.size(), totalQtyRestored, restockedProducts.size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Inventory findByProductId(String productId) {
        return inventoryRepo.findByProductId(productId)
                .orElseThrow(() -> new NotFoundException(
                        "Inventory not found for product: " + productId));
    }

    private Inventory findOwnedInventory(String productId, String merchantId) {
        Inventory inventory = findByProductId(productId);
        if (!inventory.getMerchantId().equals(merchantId)) {
            throw new ForbiddenException("Inventory not owned by merchant: " + merchantId);
        }
        return inventory;
    }
}