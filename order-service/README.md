# Order Service — 3.5

TradeFlow's SAGA orchestrator and event-sourced order ledger.  
**Spring Boot 3.2 · Java 21 · PostgreSQL · Kafka · Event Sourcing · SAGA**

---

## Architecture Overview

This service has **two runtime roles** in the same Spring Boot application:

1. **HTTP Server** — buyer/merchant/admin REST API (10 endpoints)
2. **Kafka Consumer Group** — SAGA event handlers (3 consumer groups, 4 event types)

The combination makes it the most connected service in the platform — it is the orchestrator that coordinates Inventory, Fraud Detection, Payment, and Notification services.

---

## Event Sourcing

Order state is **never stored as a mutable row** — it is the result of replaying all events in `order_events` in sequence.

```
order_events (append-only, source of truth)
  v1: ORDER_CREATED
  v2: INVENTORY_RESERVED  
  v3: FRAUD_SCORING_PASSED
  v4: PAYMENT_COMPLETED
  v5: MERCHANT_ACKNOWLEDGED
  v6: ORDER_SHIPPED
  v7: ORDER_DELIVERED
```

The `orders` table is a **materialized snapshot** — a read performance optimization kept in sync with every event write. If it ever drifts, it can be rebuilt by replaying all events. PostgreSQL is authoritative.

---

## SAGA State Machine

```
CREATED
  ↓ (inventory.reserved)
INVENTORY_RESERVED
  ↓ (fraud.score.computed: APPROVED)
PAYMENT_PENDING
  ↓ (payment.processed)
PAID
  ↓ (merchant acknowledges)
FULFILLING
  ↓ (merchant ships)
SHIPPED
  ↓ (courier webhook)
DELIVERED
  ↓ (buyer requests)
RETURN_REQUESTED

Compensation paths:
  fraud: REJECTED    → release inventory → CANCELLED
  payment: FAILED    → release inventory → CANCELLED
  recovery: TIMEOUT  → release inventory → CANCELLED
```

---

## Endpoints

| # | Method | Path | Auth | Description |
|---|--------|------|------|-------------|
| 1 | POST | `/orders` | BUYER | Create order — SAGA birth |
| 2 | GET | `/orders/{id}` | BUYER/MERCHANT | Current state + SAGA timeline |
| 3 | GET | `/orders/{id}/history` | BUYER/COMPLIANCE | Full append-only audit trail |
| 4 | GET | `/buyers/{buyerId}/orders` | BUYER | Paginated order history |
| 5 | POST | `/orders/{id}/cancel` | BUYER | Cancel (CREATED only) |
| 6 | POST | `/orders/{id}/fulfillment/acknowledge` | MERCHANT | PAID → FULFILLING |
| 7 | POST | `/orders/{id}/shipment` | MERCHANT | FULFILLING → SHIPPED |
| 8 | POST | `/orders/{id}/delivery` | SERVICE | SHIPPED → DELIVERED + payout trigger |
| 9 | POST | `/orders/{id}/return` | BUYER | DELIVERED → RETURN_REQUESTED |
| 10 | GET | `/internal/orders/{id}/status` | NetworkPolicy | SAGA participant fast-path |

---

## SAGA Kafka Consumers

| Consumer Group | Topic | Purpose |
|---|---|---|
| `order-saga-inventory` | `inventory.events` | `inventory.reserved` → send fraud command; `inventory.released` → CANCELLED |
| `order-saga-fraud` | `fraud.events` | `fraud.score.computed` → APPROVED/BLOCKED/REVIEW |
| `order-saga-payment` | `payment.events` | `payment.processed` → PAID; `payment.failed` → compensation |

---

## SAGA Recovery Job

Every 60 seconds (`@Scheduled`), ShedLock ensures one Pod runs:

| Stuck State | Timeout | Action |
|---|---|---|
| `CREATED` | 10 min | Re-emit `cmd.reserve-inventory` |
| `INVENTORY_RESERVED` | 10 min | Re-emit `cmd.score-transaction` |
| `PAYMENT_PENDING` | 15 min | Release inventory, notify buyer |

---

## Quick Start

```bash
docker compose up -d postgres kafka

./mvnw spring-boot:run

# Swagger UI
open http://localhost:8084/swagger-ui.html

# Create an order
curl -X POST http://localhost:8084/orders \
  -H "Authorization: Bearer <buyer-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "buyerId":"usr_001",
    "items":[{"productId":"prd_9x2k","merchantId":"mrc_a3f8","qty":2,"unitPrice":18999.00}],
    "shippingAddress":{"street":"Av. Insurgentes 1234","city":"CDMX","state":"CDMX","postalCode":"03100","country":"MX"},
    "paymentMethodToken":"pm_4242424242",
    "currency":"MXN",
    "idempotencyKey":"cart_abc_001"
  }'

# Poll status
curl http://localhost:8084/orders/ord_xxx -H "Authorization: Bearer <token>"

# Full audit trail
curl http://localhost:8084/orders/ord_xxx/history -H "Authorization: Bearer <token>"
```

---

## Outbox Pattern

Every SAGA command is written to `order_outbox` in the **same `@Transactional`** block as the `order_events` INSERT. This means:
- No SAGA commands are ever lost even if Kafka is down
- Outbox relay (500ms, `SELECT FOR UPDATE SKIP LOCKED`) publishes when Kafka recovers
- Multi-pod safe — SKIP LOCKED prevents duplicate publishes

---

## Tech Stack

| Component | Technology |
|---|---|
| Runtime | Spring Boot 3.2, Java 21 Virtual Threads |
| State store | PostgreSQL — `order_events` (source of truth) + `orders` snapshot |
| Command/event bus | Kafka + Outbox Pattern |
| Pre-validation | Resilience4j: Bulkhead → CircuitBreaker → Retry → TimeLimiter |
| Recovery | `@Scheduled` (60s) + ShedLock — self-healing SAGA |
| Schema | Flyway |
