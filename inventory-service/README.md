# Inventory Service — 3.4

TradeFlow's stock management — two-phase reserve/confirm/release with optimistic locking.  
**Spring Boot 3.2 · Java 21 · PostgreSQL only · Kafka · ShedLock · Resilience4j**

---

## The Core Design Insight

`available_qty` is **never stored**. It is always computed:

```sql
available_qty = total_qty - SUM(
    SELECT qty FROM stock_reservations
    WHERE inventory_id = ?
    AND status = 'PENDING'
    AND expires_at > NOW()
)
```

Storing it creates a race condition where two concurrent buyers both read `available_qty=1`, both decrement to 0, and both succeed — overselling the last unit. Dynamic computation + `@Version` optimistic locking makes PostgreSQL the single arbiter of truth.

---

## Two-Phase Protocol

```
PHASE 1: RESERVE  →  soft-lock N units (total_qty unchanged, reservation row PENDING)
                      expires in 10 minutes if not resolved

PHASE 2a: CONFIRM →  payment succeeded → reservation CONFIRMED + total_qty decremented
PHASE 2b: RELEASE →  payment failed / fraud blocked → reservation RELEASED
                      total_qty unchanged, available restores automatically

TTL EXPIRY:       →  buyer abandoned → @Scheduled cleanup marks EXPIRED
                      available restores automatically (EXPIRED excluded from formula)
```

---

## Endpoints

| # | Method | Path | Auth | Description |
|---|--------|------|------|-------------|
| 1 | POST | `/inventory` | SERVICE/ADMIN | Initialize inventory (called from product.created Kafka event) |
| 2 | GET | `/inventory/{productId}` | MERCHANT | Full stock detail + live reservations (no cache) |
| 3 | PUT | `/inventory/{productId}/stock` | MERCHANT | Restock — add units |
| 4 | POST | `/inventory/{productId}/reserve` | SERVICE | Phase 1 — soft-lock N units for checkout |
| 5 | POST | `/inventory/{productId}/confirm` | SERVICE | Phase 2a — payment succeeded, permanent decrement |
| 6 | POST | `/inventory/{productId}/release` | SERVICE | Phase 2b — payment failed, release soft-lock |
| 7 | PUT | `/inventory/{productId}/threshold` | MERCHANT | Configure low-stock alert threshold |
| 8 | GET | `/internal/inventory/{productId}/available` | NetworkPolicy | Fast pre-check for Order Service SAGA |

---

## The Two-Buyer Race

```
Time 0ms: Buyer A reads inventory (total=1, reserved=0, available=1, version=3)
Time 0ms: Buyer B reads inventory (total=1, reserved=0, available=1, version=3)

Time 2ms: Buyer A: UPDATE inventory SET version=4 WHERE version=3 → SUCCESS → COMMIT
Time 2ms: Buyer B: UPDATE inventory SET version=4 WHERE version=3 → 0 ROWS → OptimisticLockException

Time 3ms: Buyer B: Resilience4j RETRY #1
          → re-reads: available = 1 - 1 = 0 → 409 INSUFFICIENT_STOCK

Result: Buyer A gets the unit. Buyer B sees "just sold out." No oversell.
```

---

## Quick Start

```bash
docker compose up -d postgres kafka

./mvnw spring-boot:run

# Swagger UI
open http://localhost:8083/swagger-ui.html

# Reserve units (Order Service call)
curl -X POST http://localhost:8083/inventory/prd_9x2k/reserve \
  -H "Authorization: Bearer <service-token>" \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ord_abc123","buyerId":"usr_001","qty":2,"idempotencyKey":"ord_abc123-reserve"}'

# Internal availability check (from Order Service only)
curl http://localhost:8083/internal/inventory/prd_9x2k/available?qty=2
```

---

## Kafka Events Published

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `inventory.initialized` | Product created | Search, Analytics |
| `inventory.restocked` | Stock added or reservation released/expired when OUT_OF_STOCK | Search, Notification (wishlist alerts) |
| `inventory.reserved` | Phase 1 success | Order Service (SAGA advance) |
| `inventory.confirmed` | Phase 2a success | Order Service (SAGA advance → PAID) |
| `inventory.released` | Phase 2b (compensation) | Order Service (SAGA → CANCELLED) |
| `inventory.low-stock` | available_qty drops below threshold | Notification (merchant alert), Search (badge) |
| `inventory.out-of-stock` | available_qty reaches 0 | Search (filter), Notification |

---

## TTL Cleanup Job

Every 5 minutes (`@Scheduled`), ShedLock ensures only one of N Pods runs it.

Covers three failure scenarios:
1. **Buyer closes browser** — no confirm/release arrives, reservation expires
2. **Order Service crashes mid-SAGA** — reservation exists with no resolution command
3. **Payment Service down > 10min** — reservation expires before payment completes

In all cases: EXPIRED rows are excluded from the `available_qty` formula. Units restore automatically. No `UPDATE inventory.total_qty` needed.

---

## Tech Stack

| Component | Technology | Reason |
|-----------|-----------|--------|
| Database | PostgreSQL only | All state in one store — no secondary read model needed |
| Locking | JPA `@Version` | Optimistic compare-and-swap — no pessimistic row locks |
| Retry | Resilience4j `@Retry` | 3 attempts on `OptimisticLockException`, exponential backoff |
| Scheduling | `@Scheduled` + ShedLock | TTL cleanup, single Pod wins per 5-minute window |
| Events | Kafka + Outbox Pattern | Zero lost events under Kafka downtime |
| Schema | Flyway | Versioned migrations |
