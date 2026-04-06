# Fraud Detection Service — 3.7

TradeFlow's ML fraud scoring engine with live rule management and model feedback loop.  
**Spring Boot 3.2 · Java 21 Virtual Threads · Spring AI · MongoDB Atlas Vector Search · Redis · Spring Batch**

---

## ML Architecture

### Scoring Pipeline (7 steps)

```
1. Parallel context enrichment (10-30ms)
   MongoDB buyer_baseline + Redis OFAC/VPN check + Redis merchant cache
   → All four happen simultaneously via CompletableFuture.allOf()

2. Cold-start check (< 1ms)
   transaction_count < 3 AND amount > MXN $3,200 → BLOCK immediately
   No ML needed — hard business rule for new accounts

3. Rule evaluation (< 1ms — in-memory volatile List)
   BLOCK rules → short-circuit (skip ML entirely, saves 150ms)
   ALLOW rules → set fast-path flag for ML override
   REVIEW rules → floor score at 0.65

4. Feature vector assembly
   Structured text string: buyer_id, velocity, deviation, device, IP, etc.

5. Spring AI EmbeddingClient (50-150ms)
   feature_string → float[1536]
   Virtual Threads: this HTTP call doesn't park a carrier thread

6. MongoDB Atlas $vectorSearch (20-50ms)
   queryVector: float[1536], filter: { label: "FRAUD" }
   Returns top-20 nearest fraud neighbors with cosine similarity scores

7. Score assembly + persist + Kafka publish
   raw = (0.60 × ML) + (0.20 × amount_dev) + (0.10 × velocity) + (0.10 × device_ip)
   Rules applied → final decision: APPROVED / REVIEW / BLOCKED
```

### Score Formula

```
decision thresholds:
  ≥ 0.85 → BLOCKED
  ≥ 0.65 → REVIEW (manual review queue)
  < 0.65 → APPROVED
```

---

## Endpoints

| # | Method | Path | Auth | Description |
|---|--------|------|------|-------------|
| 1 | POST | `/fraud/score` | SERVICE | Full ML + rules pipeline |
| 2 | GET | `/fraud/scores/{orderId}` | COMPLIANCE | Full explanation with neighbors + context |
| 3 | GET | `/fraud/buyers/{buyerId}/profile` | COMPLIANCE | Risk profile + behavioral baseline |
| 4 | POST | `/fraud/rules` | ADMIN | Create rule — immediate enforcement |
| 5 | PUT | `/fraud/rules/{ruleId}` | ADMIN | Update rule |
| 6 | GET | `/fraud/rules` | ADMIN | List rules with effectiveness metrics |
| 7 | DELETE | `/fraud/rules/{ruleId}` | ADMIN | Deactivate rule (soft delete) |
| 8 | POST | `/fraud/feedback` | SERVICE | Confirm fraud — model feedback loop |
| 9 | GET | `/internal/fraud/buyers/{buyerId}/baseline` | NetworkPolicy | Order Service CB fallback |

---

## Live Rule Engine

Rules stored in MongoDB, evaluated from in-memory `volatile List<FraudRule>`, refreshed every 60 seconds. New BLOCK rules take effect within 60 seconds — **no redeployment needed**.

```
Compliance officer adds BLOCK rule for compromised merchant at 2:01 AM
→ In-memory cache updated immediately (same Pod) + 60s for other Pods
→ All new transactions from that merchant are blocked
→ Attack neutralized in < 2 minutes
```

**Supported operators:** EQUALS, NOT_EQUALS, GREATER_THAN, LESS_THAN, IN, NOT_IN, CONTAINS, IP_IN_REDIS_SET, REGEX_MATCH

---

## Model Feedback Loop

```
Transaction APPROVED → later confirmed as fraud
    ↓
fraud.confirmed Kafka event (from Payment Service on dispute win)
    ↓
fraud_vectors.label: LEGITIMATE → FRAUD
buyer_baselines.risk_tier: STANDARD → HIGH_RISK
Redis: SADD suspicious:ips {attacker_ip}
    ↓
Sunday 3 AM — Spring Batch retraining job
$vectorSearch now finds this vector when scoring similar transactions
Model version incremented: v2024-W47
No redeployment needed
```

---

## Data Stores

| Store | Collections |
|---|---|
| MongoDB Atlas | `fraud_vectors` (1536-dim embeddings + labels), `fraud_rules`, `buyer_baselines`, `fraud_feedback` |
| PostgreSQL | `fraud_scores_audit` (compliance record), Spring Batch job tables |
| Redis | `ofac:ips`, `vpn:ips`, `tor:exits`, `suspicious:ips`, `compromised:devices` (all Redis SETs, O(1) SISMEMBER) |

---

## MongoDB Atlas Vector Search Index

**Required for production** — must be created in Atlas UI:

```json
{
  "mappings": {
    "dynamic": false,
    "fields": {
      "embedding": { "type": "knnVector", "dimensions": 1536, "similarity": "cosine" },
      "label":     { "type": "string" },
      "buyer_id":  { "type": "string" }
    }
  }
}
```

**Local dev**: Vector search falls back gracefully (ml_score = 0, rules-only mode still fully functional).

---

## Quick Start

```bash
export OPENAI_API_KEY=sk-...

docker compose up -d postgres mongodb redis kafka
./mvnw spring-boot:run

# Swagger UI
open http://localhost:8086/swagger-ui.html

# Score a transaction
curl -X POST http://localhost:8086/fraud/score \
  -H "Authorization: Bearer <service-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "orderId":"ord_test1",
    "buyerId":"usr_001",
    "merchantId":"mrc_a3f8",
    "amount":37998.00,
    "currency":"MXN",
    "productCategories":["ELECTRONICS"],
    "deviceFingerprint":"sha256:abc123",
    "ipAddress":"201.144.1.1"
  }'

# Add a BLOCK rule (no redeployment needed)
curl -X POST http://localhost:8086/fraud/rules \
  -H "Authorization: Bearer <admin-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "ruleName":"TEST_BLOCK_HIGH_AMOUNT",
    "ruleType":"BLOCK",
    "condition":{"field":"amount","operator":"GREATER_THAN","value":50000},
    "priority":1,
    "createdBy":"admin"
  }'
```
