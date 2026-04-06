# Payment Service — 3.6

TradeFlow's financial gateway — the only service that handles real money.  
**Quarkus 3.x · GraalVM Native · Mutiny Reactive · PostgreSQL · MongoDB · Redis · Stripe**

---

## Architecture: Why Reactive?

Under a flash sale with 1,000 simultaneous charges, a blocking Spring Boot approach would park 1,000 threads (~1GB RAM) waiting for Stripe's 200-500ms responses. With **Quarkus Mutiny reactive**, 10-20 event loop threads handle all 1,000 concurrently through non-blocking `Uni<>` pipelines.

The Vert.x event loop thread is **never parked** waiting for Stripe. When a Stripe call is in-flight, the thread immediately picks up the next payment. When Stripe responds, the event loop resumes the pipeline.

---

## The Idempotency Contract

**Money either moves correctly or not at all — never in an ambiguous state.**

Every charge uses an `idempotency_key = orderId`. The `payments_idempotency` table is checked before every Stripe call. This prevents double charges under:
- Network timeouts (Order Service retries)
- Kafka redelivery (at-least-once consumer semantics)
- SAGA Recovery Job re-emitting charge commands
- Any other retry scenario

---

## Endpoints

| # | Method | Path | Auth | Description |
|---|--------|------|------|-------------|
| 1 | POST | `/payments/charge` | SERVICE | Charge buyer — full reactive Uni<> pipeline |
| 2 | POST | `/payments/refund` | SERVICE/ADMIN | Full or partial Stripe refund |
| 3 | POST | `/payments/webhooks/stripe` | HMAC | Stripe webhook receiver — 200 returned immediately |
| 4 | GET | `/payments/{orderId}` | BUYER/MERCHANT | Real-time payment status |
| 5 | GET | `/payments/{orderId}/receipt` | BUYER | Immutable receipt — Redis cached |
| 6 | POST | `/internal/payments/dunning/tick` | SERVICE | Dunning scheduler — merchant fee retry |
| 7 | GET | `/internal/payments/{orderId}/status` | NetworkPolicy | SAGA Recovery fast-path |
| 8 | POST | `/payments/disputes/{disputeId}/respond` | ADMIN | Submit Stripe dispute evidence |

---

## Kafka

**Consumers:**
- `cmd.charge-payment` (group: `payment-saga-charge`) — SAGA charge command
- `refund.requested` (group: `payment-saga-refund`) — return flow refund command

**Buffer:** max 500 pending items. Over limit → FAIL_FAST → 429 → Order Service Circuit Breaker opens → buffer drains → CB closes.

**Producers (via Outbox Pattern):**
- `payment.events` — payment.processed, payment.failed, refund.completed, dispute.created
- `order.events` — merchant.status.changed (from dunning)

---

## Dunning State Machine

```
MERCHANT FEE FAILED
  ↓ Day 0
GRACE_PERIOD (notify, no action)
  ↓ Day 3 — automated retry
RETRY_1
  ↓ Day 7 — final retry
RETRY_2
  ↓ Day 14 — SUSPEND via merchant.status.changed event
SUSPENDED → Identity Service cascade
  ↓ Day 30 — TERMINATE via merchant.terminated event
TERMINATED → full cleanup cascade
```

Dunning events are identical to admin-triggered suspension events — downstream services (Identity, Catalog) handle them the same way.

---

## Data Stores

| Store | Purpose |
|---|---|
| PostgreSQL | `payments`, `payments_idempotency`, `webhook_events`, `disputes`, `dunning_cases`, `payment_outbox` |
| MongoDB | `payment_audit` — raw Stripe responses, webhook history, dispute evidence (schemaless, immutable) |
| Redis | Receipt cache (immutable once generated, TTL 7 days) |

---

## Quick Start

```bash
# Set your Stripe test keys
export STRIPE_SECRET_KEY=sk_test_...
export STRIPE_WEBHOOK_SECRET=whsec_...

docker compose up -d postgres mongodb redis kafka

# JVM mode (fast iteration):
./mvnw quarkus:dev

# Native build (production):
./mvnw package -Pnative

# Swagger UI
open http://localhost:8085/q/swagger-ui

# Test charge
curl -X POST http://localhost:8085/payments/charge \
  -H "Authorization: Bearer <service-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "orderId":"ord_abc123",
    "buyerId":"usr_001",
    "amount":37998.00,
    "currency":"MXN",
    "paymentMethodToken":"pm_card_visa",
    "merchantId":"mrc_a3f8",
    "idempotencyKey":"ord_abc123"
  }'

# Check SAGA recovery path
curl http://localhost:8085/internal/payments/ord_abc123/status
```

---

## GraalVM Native

- JVM mode: `./mvnw quarkus:dev` (hot reload, ~3s startup)
- Native build: `./mvnw package -Pnative` (~3 min compile, ~8ms startup)
- Production: native binary in `target/payment-service-1.0.0-runner`

Native startup of ~8ms means rolling deploys complete before Stripe retries any in-flight webhooks. No JVM warmup, predictable low-latency from the first request.
