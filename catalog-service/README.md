# Product Catalog Service — 3.3

TradeFlow's product catalog — dual database CQRS, Redis pre-warming, promotional campaign engine.  
**Spring Boot 3.2 · Java 21 · PostgreSQL + MongoDB · Redis · Kafka · ShedLock**

---

## Architecture

```
WRITE FLOW (dual-DB: PostgreSQL first, MongoDB second)
──────────────────────────────────────────────────────
POST /products
    → Step 1: PostgreSQL (ACID) — price, status, stock (source of truth)
              + catalog_outbox row (same @Transactional)
    → Step 2: MongoDB (presentation layer) — attributes, description, media
    → Outbox relay (500ms) → Kafka: product.created
    → Search Service indexes product in Elasticsearch

READ FLOW (Cache-Aside: Redis → parallel PG + MongoDB)
──────────────────────────────────────────────────────
GET /products/{id}
    → Redis (top 1000 pre-warmed at startup, TTL 5min) → HIT: < 1ms
    → MISS: parallel fetch PG (price/status) + MongoDB (attributes/media) → ~15ms
    → Assemble combined response → cache → return

CAMPAIGN ENGINE (@Scheduled every 60s, ShedLock — single Pod)
──────────────────────────────────────────────────────
tickCampaigns():
    → Activate: promotional_start <= NOW() → set promo price, evict cache
    → Revert:   promotional_end   <= NOW() → restore original price, evict cache
    → Each transition fires product.price.changed → Search re-indexes
```

---

## Endpoints

| # | Method | Path | Auth | Description |
|---|--------|------|------|-------------|
| 1 | POST | `/products` | MERCHANT | Create listing (PG + MongoDB dual write) |
| 2 | GET | `/products/{id}` | public | Full detail (Redis → parallel PG+MongoDB) |
| 3 | PUT | `/products/{id}` | MERCHANT | Update title/status/attributes (@Version OL) |
| 4 | PUT | `/products/{id}/price` | MERCHANT | FIXED \| TIERED \| PROMOTIONAL pricing |
| 5 | POST | `/products/{id}/delist` | MERCHANT/ADMIN | Soft delete → Kafka cascade |
| 6 | POST | `/products/{id}/media` | MERCHANT | S3 upload → MongoDB media array |
| 7 | GET | `/categories` | public | Full category tree (Redis TTL 1hr) |
| 8 | GET | `/categories/{id}/products` | public | Paginated browse (PG + batch MongoDB) |
| 9 | GET | `/merchants/{id}/products` | MERCHANT | Dashboard list — no cache, real-time |
| 10 | POST | `/internal/campaigns/tick` | admin/internal | Promotional pricing engine trigger |

---

## Quick Start

```bash
docker compose up -d postgres mongodb redis kafka

./mvnw spring-boot:run

# Swagger UI
open http://localhost:8082/swagger-ui.html

# Kafka UI
open http://localhost:8091

# Browse electronics category
curl http://localhost:8082/categories/cat_electronics_phones/products
```

---

## Kafka Topics

| Topic | Events | Consumers |
|-------|--------|-----------|
| `catalog.product-events` | product.created, product.updated, product.price.changed, product.delisted | Search Service, Fraud Detection, Analytics, Order Service |
| `identity.merchant-events` | merchant.status.changed, merchant.terminated | Catalog Service (delist all products on suspend/terminate) |

---

## Cache Pre-Warming

On every Pod startup (`@PostConstruct`), before the Readiness Probe passes:
1. Query PostgreSQL for top 1,000 products by `sales_volume`
2. Batch fetch MongoDB attributes (50 products per batch)
3. Assemble and store in Redis with 1hr TTL

This means **zero cold cache hits** on rolling deployments — the new Pod is fully warm before traffic is routed to it.

---

## ShedLock — Campaign Tick

With N Pods running `@Scheduled(fixedRate=60_000)`, all N Pods would try to transition campaigns simultaneously — publishing N duplicate `product.price.changed` events.

ShedLock stores a lock row in PostgreSQL:
```sql
-- Only the Pod that wins the lock runs the job
SELECT ... FOR UPDATE on shedlock WHERE name='catalog-campaigns-tick' AND lock_until < NOW()
```
The winning Pod runs the tick and holds the lock for 55s. Other Pods skip. Clean, no duplicates.

---

## N+1 Prevention

Paginated browse (`GET /categories/{id}/products`, page=24) would naively make 24 individual MongoDB calls for thumbnail URLs — an N+1 disaster.

Instead, one batch call after the PostgreSQL query:
```java
List<String> ids = postgresPage.stream().map(Product::getId).toList();
Map<String, ProductDetail> detailMap = detailRepo.findByProductIdIn(ids).stream()
    .collect(toMap(ProductDetail::getProductId, d -> d));
// ONE MongoDB query for the entire page
```

---

## Tech Stack

| Component | Technology | Why |
|-----------|-----------|-----|
| Runtime | Spring Boot 3.2 / Java 21 | Virtual Threads for parallel PG+MongoDB fetch |
| Write DB | PostgreSQL | ACID — price, status, stock reference (source of truth) |
| Read DB | MongoDB | Flexible attributes (category-specific schema), media array |
| Cache | Redis | Pre-warmed top-1000; 5min TTL single products; 2min browse pages; 1hr category tree |
| Events | Kafka + Outbox | product.created/updated/price.changed/delisted — drives Search, Fraud, Analytics |
| Locking | JPA @Version | Optimistic locking on product edits — 409 on concurrent clash |
| Scheduling | ShedLock | Campaign tick distributed lock — one Pod wins per 60s window |
| Media | AWS S3 + CloudFront | Product images stored in S3, served via CDN |
| Schema | Flyway | Versioned SQL migrations (categories seeded) |
