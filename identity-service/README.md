# Identity & Merchant Service — 3.2

TradeFlow's identity and merchant lifecycle service.  
**Java 21 Virtual Threads · Spring Boot 3.2 · CQRS · Outbox Pattern · KYC State Machine**

---

## Architecture

```
WRITE PATH (PostgreSQL — source of truth, ACID)
─────────────────────────────────────────────────────────────────────
Client Request
    → Spring Controller (Virtual Thread)
    → @Service (state machine validation)
    → JPA → PostgreSQL
    → identity_outbox (same @Transactional)
    → Outbox Relay (every 500ms, SKIP LOCKED)
    → Kafka

READ PATH (Redis → MongoDB — never PostgreSQL for reads)
─────────────────────────────────────────────────────────────────────
Order Service / API Client
    → GET /buyers/{id}/profile or /internal/merchants/{id}/status
    → Redis (TTL 10 min)          → HIT: < 1ms
    → MongoDB buyer_profiles      → MISS: ~5ms
    → Cache result in Redis

READ MODEL REBUILD (Kafka consumer → MongoDB)
─────────────────────────────────────────────────────────────────────
buyer.registered / buyer.profile.updated
    → Identity Service consumer
    → Rebuild buyer_profiles document
    → MongoDB write
```

---

## Endpoints

| # | Method | Path | Auth | Description |
|---|--------|------|------|-------------|
| 1 | POST | `/buyers/register` | Bearer | Register new buyer (PostgreSQL + Outbox) |
| 2 | GET | `/buyers/{id}/profile` | Bearer | Read profile (Redis → MongoDB) |
| 3 | PUT | `/buyers/{id}/profile` | Bearer | Update profile (PG → Redis evict → Kafka → MongoDB) |
| 4 | POST | `/merchants/onboard` | Bearer | Start KYC state machine |
| 5 | GET | `/merchants/{id}/status` | Bearer | Merchant status (Redis → PG) |
| 6 | POST | `/merchants/{id}/kyc/documents` | Bearer | Upload KYC docs → S3 → Jumio |
| 7 | POST | `/merchants/{id}/suspend` | ADMIN/COMPLIANCE | Compliance hold (immediate Redis bust) |
| 8 | POST | `/merchants/{id}/terminate` | ADMIN | Permanent termination (fan-out via Kafka) |
| 9 | GET | `/internal/merchants/{id}/status` | NetworkPolicy | Order Service hot path (Redis only) |
| – | POST | `/internal/kyc/webhook` | HMAC | Jumio KYC result callback |

---

## Merchant State Machine

```
PENDING_KYC
    │ (upload docs → Jumio)
    ▼
KYC_IN_PROGRESS
    ├── Jumio: APPROVED → ACTIVE ─── normal operation
    │                         ├── SUSPENDED (compliance hold)
    │                         └── TERMINATED (severe violation)
    └── Jumio: REJECTED → TERMINATED
```

---

## Quick Start

```bash
# 1. Start all dependencies
docker compose up -d postgres mongodb redis kafka

# 2. Wait for health checks, then start the service
./mvnw spring-boot:run

# 3. Swagger UI
open http://localhost:8080/swagger-ui.html

# 4. Kafka UI
open http://localhost:8090

# Test: register a buyer
curl -X POST http://localhost:8080/buyers/register \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token-from-auth-service>" \
  -d '{
    "email": "buyer@test.com",
    "full_name": "Test Buyer",
    "phone": "+52 55 1234 5678",
    "address": {
      "street": "Av. Insurgentes Sur 1234",
      "city": "Ciudad de México",
      "state": "CDMX",
      "postal_code": "03100",
      "country": "MX"
    },
    "locale": "es_MX"
  }'
```

---

## Kafka Topics

| Topic | Producer | Consumers |
|-------|----------|-----------|
| `identity.buyer-events` | Identity Service (outbox) | Identity Service (MongoDB rebuild), Notification Service |
| `identity.merchant-events` | Identity Service (outbox) | Product Catalog, Payment, Notification, Order |

---

## Virtual Threads — Why It Matters Here

The Jumio API call at `POST /merchants/{id}/kyc/documents` blocks for **2–3 seconds**.  

With traditional platform threads (Tomcat default):
- Each blocked request holds a platform thread (1MB stack)
- 500 concurrent KYC uploads = 500MB of stack memory just for waiting
- Thread pool exhaustion → service unresponsive

With `spring.threads.virtual.enabled=true` (Java 21):
- Each blocked request uses a virtual thread (a few KB)
- 10,000 concurrent uploads → 10,000 virtual threads → ~10MB overhead
- Carrier threads park during I/O wait → zero platform thread waste

---

## Tech Stack

| Component | Technology | Why |
|-----------|-----------|-----|
| Runtime | Spring Boot 3.2 / Java 21 | Virtual Threads for Jumio blocking calls |
| Write DB | PostgreSQL | ACID — user profiles, KYC status, contracts |
| Read DB | MongoDB | Denormalized buyer_profiles — single fetch at checkout |
| Cache | Redis | Sub-ms merchant status for Order Service; buyer profile TTL |
| Events | Kafka + Outbox Pattern | Zero event loss between PG write and Kafka publish |
| KYC | Jumio + Resilience4j | CircuitBreaker + Retry + TimeLimiter wraps external API |
| Scheduling | ShedLock | Distributed lock for any @Scheduled jobs |
| Schema | Flyway | Versioned SQL migrations |
| Docs | SpringDoc / OpenAPI 3 | Swagger UI at `/swagger-ui.html` |

---

## Kubernetes

```bash
kubectl apply -f k8s/identity-service.yaml

# HPA: 2–10 replicas, CPU 70% / Memory 80%
# PDB: minAvailable=1 (never evict all pods)
# NetworkPolicy: only api-gateway + order-service can reach this service
```
