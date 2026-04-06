-- V1__initial_schema.sql
-- Inventory Service — complete schema
-- Single database: PostgreSQL only (by design — no Redis, no MongoDB)
-- Flyway runs this automatically on startup

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ─────────────────────────────────────────────────────────────────────────────
-- INVENTORY
--
-- CRITICAL: available_qty is NOT stored here.
-- It is always computed dynamically:
--   available_qty = total_qty - SUM(
--       SELECT qty FROM stock_reservations
--       WHERE inventory_id = ?
--       AND status = 'PENDING'
--       AND expires_at > NOW()
--   )
--
-- Storing it would create a race condition: two concurrent reservations
-- could both read available_qty=1, both decrement to 0, both succeed → oversell.
-- Dynamic computation + @Version optimistic lock prevents this.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE inventory (
    id                      VARCHAR(36)     PRIMARY KEY,
    product_id              VARCHAR(36)     NOT NULL UNIQUE,
    merchant_id             VARCHAR(36)     NOT NULL,
    sku                     VARCHAR(100),
    total_qty               INT             NOT NULL DEFAULT 0,
    low_stock_threshold     INT             NOT NULL DEFAULT 10,
    reservation_ttl_minutes INT             NOT NULL DEFAULT 10,
    status                  VARCHAR(20)     NOT NULL DEFAULT 'IN_STOCK',
    -- @Version column — JPA optimistic lock.
    -- Every write fires: UPDATE ... SET version=version+1 WHERE version=?
    -- Two concurrent writers → one wins, other retries (Resilience4j, 3 attempts).
    version                 BIGINT          NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_inventory_qty        CHECK (total_qty >= 0),
    CONSTRAINT chk_inventory_threshold  CHECK (low_stock_threshold > 0),
    CONSTRAINT chk_inventory_ttl        CHECK (reservation_ttl_minutes > 0),
    CONSTRAINT chk_inventory_status     CHECK (status IN ('IN_STOCK', 'LOW_STOCK', 'OUT_OF_STOCK'))
);

CREATE INDEX idx_inventory_product_id  ON inventory(product_id);
CREATE INDEX idx_inventory_merchant_id ON inventory(merchant_id);
CREATE INDEX idx_inventory_status      ON inventory(status);

COMMENT ON TABLE inventory IS 'Single source of truth for stock. available_qty computed dynamically from reservations.';
COMMENT ON COLUMN inventory.version IS '@Version optimistic lock. Prevents overselling via compare-and-swap.';
COMMENT ON COLUMN inventory.total_qty IS 'Physical stock count. Only decremented on CONFIRM (Phase 2a). Never on RESERVE.';

-- ─────────────────────────────────────────────────────────────────────────────
-- STOCK RESERVATIONS
--
-- Two-phase protocol rows:
--   PENDING   → units soft-locked during checkout (available_qty formula excludes them)
--   CONFIRMED → Phase 2a committed, total_qty decremented (row kept for history)
--   RELEASED  → Phase 2b compensation (total_qty unchanged, row excluded from formula)
--   EXPIRED   → TTL cleanup ran, buyer abandoned (total_qty unchanged, excluded from formula)
--
-- available_qty restores automatically when status transitions from PENDING
-- to RELEASED or EXPIRED — no UPDATE to inventory.total_qty needed.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE stock_reservations (
    id              VARCHAR(36)     PRIMARY KEY,
    inventory_id    VARCHAR(36)     NOT NULL REFERENCES inventory(id),
    order_id        VARCHAR(36)     NOT NULL,
    buyer_id        VARCHAR(36)     NOT NULL,
    qty             INT             NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    idempotency_key VARCHAR(200)    NOT NULL UNIQUE,  -- prevents duplicate reserves on retry
    release_reason  VARCHAR(100),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ     NOT NULL,          -- created_at + reservation_ttl_minutes
    resolved_at     TIMESTAMPTZ,

    CONSTRAINT chk_reservation_qty    CHECK (qty > 0),
    CONSTRAINT chk_reservation_status CHECK (status IN ('PENDING', 'CONFIRMED', 'RELEASED', 'EXPIRED'))
);

CREATE INDEX idx_reservations_inventory_id  ON stock_reservations(inventory_id);
CREATE INDEX idx_reservations_order_id      ON stock_reservations(order_id);
-- Critical index for available_qty computation query (called on every reserve/confirm/read)
CREATE INDEX idx_reservations_active        ON stock_reservations(inventory_id, status, expires_at)
    WHERE status = 'PENDING';
-- Critical index for TTL cleanup job (every 5 minutes)
CREATE INDEX idx_reservations_expiry        ON stock_reservations(expires_at)
    WHERE status = 'PENDING';

COMMENT ON TABLE stock_reservations IS 'Two-phase reservation rows. PENDING rows are included in available_qty formula.';
COMMENT ON COLUMN stock_reservations.idempotency_key IS 'Prevents duplicate reservations on Order Service retry. UNIQUE constraint.';
COMMENT ON COLUMN stock_reservations.expires_at IS 'Reservation TTL. Cleanup job marks EXPIRED after this. Units restore automatically.';

-- ─────────────────────────────────────────────────────────────────────────────
-- INVENTORY OUTBOX
-- Transactional outbox — every state change writes a row in the same @Transaction.
-- Relay job (500ms, SKIP LOCKED) forwards to Kafka.
-- Zero lost events under Kafka downtime.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE inventory_outbox (
    id           VARCHAR(36)     PRIMARY KEY DEFAULT gen_random_uuid()::varchar,
    event_type   VARCHAR(100)    NOT NULL,
    payload      JSONB           NOT NULL,
    aggregate_id VARCHAR(36),
    published    BOOLEAN         NOT NULL DEFAULT FALSE,
    published_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_inventory_outbox_unpublished ON inventory_outbox(published, created_at)
    WHERE published = FALSE;

-- ─────────────────────────────────────────────────────────────────────────────
-- SHEDLOCK
-- Distributed lock for @Scheduled cleanup job — one Pod wins per 5-minute window.
-- Same pattern as Catalog Service (campaign tick).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE shedlock (
    name        VARCHAR(64)     NOT NULL,
    lock_until  TIMESTAMPTZ     NOT NULL,
    locked_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    locked_by   VARCHAR(255)    NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);

COMMENT ON TABLE shedlock IS 'TTL cleanup runs every 5min. ShedLock ensures only 1 of N Pods runs it.';
