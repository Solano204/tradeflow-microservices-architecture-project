-- V1__initial_schema.sql
-- Order Service — complete schema
-- Event sourcing: order_events is the source of truth
-- orders table is a materialized snapshot (projection) — performance optimization

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ─────────────────────────────────────────────────────────────────────────────
-- ORDER EVENTS — Append-only event store. SOURCE OF TRUTH.
--
-- Every state transition appends a row. Never updated. Never deleted.
-- State is rebuilt by replaying these events in version order.
--
-- Enables:
--   - Perfect audit trail (compliance, financial disputes)
--   - Time travel: reconstruct state at any historical version
--   - SAGA debugging: see which step failed and when
--   - Recovery: restart SAGA from last known-good event
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE order_events (
    id              VARCHAR(36)     PRIMARY KEY DEFAULT gen_random_uuid()::varchar,
    order_id        VARCHAR(36)     NOT NULL,
    event_type      VARCHAR(60)     NOT NULL,
    version         INT             NOT NULL,
    event_payload   JSONB           NOT NULL,
    causation_id    VARCHAR(100),   -- which command/message triggered this event
    correlation_id  VARCHAR(36),    -- original order_id for distributed tracing
    occurred_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_order_event_version UNIQUE (order_id, version)
);

-- Queries: replay all events for an order (ascending version)
CREATE INDEX idx_order_events_order_version ON order_events(order_id, version ASC);
-- Queries: find events by type (analytics, debugging)
CREATE INDEX idx_order_events_type ON order_events(event_type, occurred_at);

COMMENT ON TABLE order_events IS 'Append-only event store. Source of truth. Never updated or deleted.';
COMMENT ON COLUMN order_events.version IS 'Monotonic per-order counter (1, 2, 3...). State = replay of all events in order.';
COMMENT ON COLUMN order_events.causation_id IS 'HTTP request UUID or Kafka message offset that caused this event.';

-- ─────────────────────────────────────────────────────────────────────────────
-- ORDERS — Materialized snapshot (projection)
--
-- A performance optimization so reads don't replay N events per request.
-- Updated in the SAME @Transactional as the order_events INSERT.
-- If snapshot is inconsistent, rebuild by replaying all events.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE orders (
    id                  VARCHAR(36)     PRIMARY KEY,
    buyer_id            VARCHAR(36)     NOT NULL,
    merchant_id         VARCHAR(36)     NOT NULL,
    status              VARCHAR(40)     NOT NULL DEFAULT 'CREATED',
    total_amount        DECIMAL(14,2)   NOT NULL,
    currency            VARCHAR(3)      NOT NULL DEFAULT 'MXN',
    shipping_address    JSONB,
    payment_method_token VARCHAR(200),
    reservation_id      VARCHAR(36),    -- set after INVENTORY_RESERVED
    charge_id           VARCHAR(100),   -- set after PAYMENT_COMPLETED
    tracking_number     VARCHAR(100),   -- set after ORDER_SHIPPED
    idempotency_key     VARCHAR(200)    NOT NULL UNIQUE,
    last_event_version  INT             NOT NULL DEFAULT 0,
    last_event_at       TIMESTAMPTZ,
    delivered_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version             BIGINT          NOT NULL DEFAULT 0,  -- @Version optimistic lock

    CONSTRAINT chk_orders_status CHECK (status IN (
        'CREATED', 'INVENTORY_RESERVED', 'FRAUD_SCORING', 'PAYMENT_PENDING',
        'PAID', 'FULFILLING', 'SHIPPED', 'DELIVERED',
        'CANCELLED', 'REJECTED', 'PAYMENT_FAILED', 'FRAUD_REVIEW',
        'RETURN_REQUESTED', 'RETURN_APPROVED', 'REFUNDED'
    ))
);

CREATE INDEX idx_orders_buyer_created   ON orders(buyer_id, created_at DESC);
CREATE INDEX idx_orders_status          ON orders(status);
CREATE INDEX idx_orders_merchant_id     ON orders(merchant_id);
CREATE INDEX idx_orders_idempotency     ON orders(idempotency_key);
-- Recovery job queries: stuck orders in intermediate states
CREATE INDEX idx_orders_status_event_at ON orders(status, last_event_at)
    WHERE status IN ('CREATED', 'INVENTORY_RESERVED', 'PAYMENT_PENDING', 'FRAUD_REVIEW');

COMMENT ON TABLE orders IS 'Snapshot projection for read performance. Source of truth is order_events.';
COMMENT ON COLUMN orders.last_event_version IS 'Version of last applied event — used to detect stale snapshots.';

-- ─────────────────────────────────────────────────────────────────────────────
-- ORDER ITEMS — Price and title snapshotted at order creation
--
-- unit_price: locked in at the exact millisecond POST /orders fires.
-- product_title: survives product delist/rename — order history stays accurate.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE order_items (
    id              VARCHAR(36)     PRIMARY KEY DEFAULT gen_random_uuid()::varchar,
    order_id        VARCHAR(36)     NOT NULL REFERENCES orders(id),
    product_id      VARCHAR(36)     NOT NULL,
    merchant_id     VARCHAR(36)     NOT NULL,
    qty             INT             NOT NULL,
    unit_price      DECIMAL(12,2)   NOT NULL,   -- IMMUTABLE after creation
    product_title   VARCHAR(300)    NOT NULL,   -- IMMUTABLE snapshot
    currency        VARCHAR(3)      NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_order_items_qty       CHECK (qty > 0),
    CONSTRAINT chk_order_items_price     CHECK (unit_price > 0)
);

CREATE INDEX idx_order_items_order_id   ON order_items(order_id);
CREATE INDEX idx_order_items_product_id ON order_items(product_id);

COMMENT ON COLUMN order_items.unit_price   IS 'Snapshotted at order creation. Merchant price changes do not affect this.';
COMMENT ON COLUMN order_items.product_title IS 'Snapshotted at order creation. Survives product delist or rename.';

-- ─────────────────────────────────────────────────────────────────────────────
-- ORDER OUTBOX — Transactional command and event bus
--
-- SAGA commands (cmd.reserve-inventory, cmd.score-transaction, cmd.charge-payment)
-- and domain events (event.order-paid, event.order-cancelled) written here
-- in the SAME @Transactional as order_events INSERT.
--
-- Outbox relay (500ms, SKIP LOCKED) publishes to Kafka.
-- Zero lost commands even if Kafka is temporarily down.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE order_outbox (
    id           VARCHAR(36)     PRIMARY KEY DEFAULT gen_random_uuid()::varchar,
    event_type   VARCHAR(100)    NOT NULL,
    payload      JSONB           NOT NULL,
    aggregate_id VARCHAR(36),
    published    BOOLEAN         NOT NULL DEFAULT FALSE,
    published_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_order_outbox_unpublished ON order_outbox(published, created_at)
    WHERE published = FALSE;

-- ─────────────────────────────────────────────────────────────────────────────
-- SHEDLOCK — SAGA Recovery @Scheduled distributed lock
-- Ensures only one of N Order Service Pods runs the recovery job per cycle.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE shedlock (
    name        VARCHAR(64)     NOT NULL,
    lock_until  TIMESTAMPTZ     NOT NULL,
    locked_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    locked_by   VARCHAR(255)    NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);

COMMENT ON TABLE shedlock IS 'SAGA recovery runs every 60s. ShedLock ensures only 1 of N Pods runs it.';
