# Search Service — 3.8

TradeFlow's product discovery layer — Elasticsearch 8 with BM25 relevance, synonym expansion, edge NGram autocomplete, geo-boost, and Kafka-driven index synchronization.  
**Spring Boot 3.2 · Java 21 Virtual Threads · Elasticsearch 8 · Kafka · Redis**

---

## Architecture

The service has two runtime roles in the same Spring Boot application:

1. **HTTP Server** — buyer-facing search, autocomplete, similar products, category browse
2. **Kafka Consumer Group** — 4 consumers keeping the ES index synchronized with Product Catalog and Inventory

The Elasticsearch index is the **source of truth for search** — fully self-contained for queries, synchronized via Kafka for writes.

---

## Endpoints

| # | Method | Path | Auth | Description |
|---|--------|------|------|-------------|
| 1 | GET | `/search/products` | Public | Full-text search with facets, BM25 + function score |
| 2 | GET | `/search/autocomplete` | Public | Sub-50ms edge NGram prefix suggestions |
| 3 | GET | `/search/products/{id}/similar` | Public | MLT — similar product recommendations |
| 4 | GET | `/search/categories/{id}/products` | Public | Category browse with search-quality ranking |
| 5 | POST | `/internal/search/index/products` | ADMIN | Force re-index single product |
| 6 | DELETE | `/internal/search/index/products/{id}` | ADMIN | Remove product from index |
| 7 | POST | `/internal/search/index/rebuild` | ADMIN | Full blue-green zero-downtime rebuild |
| 8 | GET | `/internal/search/index/health` | — | Index health + Kafka consumer lag |

---

## Elasticsearch Index Design

**`title`** has 3 simultaneous representations:
- `title` (synonym_analyzer) — "mobile phone" finds "smartphone"
- `title.keyword` — exact sort, deduplication
- `title.autocomplete` (edge_ngram) — "Sams" finds "Samsung Galaxy S24"

**Filter context vs Query context** — critical performance decision:
- Text matching → query context (BM25 score, expensive, no cache)
- Category, price, brand, availability → filter context (binary, **cached bitset**)

Same price/brand filter reused across different text queries → near-zero cost after first hit.

**Function score formula:**
```
final_score = BM25 × recency(1.3) × sales_velocity(1.2) × rating(1.1) × geo(1.15 optional)
```

---

## Autocomplete Sub-50ms Budget

```
Edge NGram at index time: "Samsung" → "Sa", "Sam", "Sams", "Samsu", "Samsun", "Samsung"
Query "sams" → standard tokenizer → "sams" → O(1) inverted index term lookup
→ No scan, no wildcard, no regex — pure hash lookup
→ Typical: 10-30ms ES execution, 22-42ms end-to-end
```

---

## Kafka Consumer Group: `search-indexer`

| Topic | Operation | Notes |
|---|---|---|
| `product.created` | ES index (full doc) | Fetches rich data from Catalog + availability from Inventory |
| `product.updated` | ES partial update | Only changed fields, `retryOnConflict: 3` |
| `product.delisted` | ES delete | Product vanishes from search within ES refresh interval (1s) |
| `inventory.restocked` | ES partial update | Only flips `available: true` — no other data re-fetched |

**Eventual consistency window: ~650ms - 1.6s**
- 500ms: outbox relay cycle
- 100ms: Kafka consumer + ES write
- 50-1000ms: ES refresh interval

---

## Circuit Breaker + Redis Fallback

Elasticsearch unavailable → Resilience4j CB opens → `searchFallback()` → Redis pre-cached top-1000 results.

The fallback is non-personalized, no facets, static ranking — but buyers see products rather than an empty page.

---

## Blue-Green Index Rebuild (Endpoint 7)

```
1. Create products_v{timestamp} with updated mapping
2. Bulk index all products (500/batch from Product Catalog)
3. Verify doc count (allow <0.1% mismatch)
4. Atomic alias swap: products → products_v{timestamp}
   (in-flight queries served seamlessly — no downtime)
5. Delete old index after 24h
```

Application code always queries the `products` alias, never a physical index name.

---

## Quick Start

```bash
docker compose up -d elasticsearch redis kafka
./mvnw spring-boot:run

# Create products index with mapping
curl -X PUT http://localhost:9200/products \
  -H "Content-Type: application/json" \
  -d @src/main/resources/elasticsearch/products-index.json

# Create products alias
curl -X POST http://localhost:9200/_aliases \
  -H "Content-Type: application/json" \
  -d '{"actions":[{"add":{"index":"products","alias":"products"}}]}'

# Search
curl "http://localhost:8087/search/products?q=samsung+galaxy&available=true"

# Autocomplete — sub-50ms
curl "http://localhost:8087/search/autocomplete?q=sams&size=8"

# Swagger UI
open http://localhost:8087/swagger-ui.html

# Kibana — ES dev tools
open http://localhost:5601
```
