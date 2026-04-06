-- V1__initial_schema.sql
-- Payment Service — complete schema
-- PostgreSQL for transactional safety (idempotency, state, outbox)
-- MongoDB for payment_audit (schemaless raw Stripe payloads — see PaymentAuditRecord.java)

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ─────────────────────────────────────────────────────────────────────────────
-- PAYMENTS
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE payments (
    id                      VARCHAR(36)     PRIMARY KEY,
    order_id                VARCHAR(36)     NOT NULL UNIQUE,
    buyer_id                VARCHAR(36)     NOT NULL,
    merchant_id             VARCHAR(36)     NOT NULL,
    amount                  DECIMAL(14,2)   NOT NULL,
    currency                VARCHAR(3)      NOT NULL DEFAULT 'MXN',
    status                  VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    stripe_charge_id        VARCHAR(100),
    payment_method_token    VARCHAR(200),
    payment_method_details  JSONB,
    processed_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                 BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT chk_payments_status CHECK (status IN (
        'PENDING','SUCCEEDED','FAILED','REFUNDED','PARTIAL_REFUND','DISPUTED'
    )),
    CONSTRAINT chk_payments_amount CHECK (amount > 0)
);

CREATE INDEX idx_payments_order_id      ON payments(order_id);
CREATE INDEX idx_payments_buyer_id      ON payments(buyer_id);
CREATE INDEX idx_payments_stripe_charge ON payments(stripe_charge_id);
CREATE INDEX idx_payments_status        ON payments(status, created_at);

COMMENT ON TABLE payments IS 'Core payment records. One row per order.';
COMMENT ON COLUMN payments.order_id IS 'UNIQUE — one order = one payment. Enforced at DB level.';

-- ─────────────────────────────────────────────────────────────────────────────
-- PAYMENTS IDEMPOTENCY — The safety net preventing double charges
--
-- idempotency_key IS the primary key (same as the key passed in the request).
-- SELECT before Stripe → INSERT after Stripe (within same transaction).
-- Concurrent identical requests: one INSERT succeeds, the other gets unique
-- violation → falls back to SELECT path → returns cached response.
-- TTL: 7 days (matches Stripe's idempotency window).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE payments_idempotency (
    idempotency_key VARCHAR(200)    PRIMARY KEY,
    order_id        VARCHAR(36)     NOT NULL,
    type            VARCHAR(20)     NOT NULL DEFAULT 'CHARGE',
    stored_response JSONB           NOT NULL,
    expires_at      TIMESTAMPTZ     NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_idempotency_order_id  ON payments_idempotency(order_id);
CREATE INDEX IF NOT EXISTS idx_idempotency_expires   ON payments_idempotency(expires_at);


COMMENT ON TABLE payments_idempotency IS
    'Prevents double charges. idempotency_key is PK — concurrent INSERTs are safe. TTL = 7 days.';

-- ─────────────────────────────────────────────────────────────────────────────
-- WEBHOOK EVENTS — Stripe webhook registry
--
-- stripe_event_id UNIQUE: deduplication for Stripe's retry-for-3-days delivery.
-- Full payload stored here (JSONB) AND in MongoDB payment_audit (schemaless blob).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE webhook_events (
    id                  VARCHAR(36)     PRIMARY KEY DEFAULT gen_random_uuid()::varchar,
    stripe_event_id     VARCHAR(100)    NOT NULL UNIQUE,
    event_type          VARCHAR(100)    NOT NULL,
    payload             JSONB           NOT NULL,
    processing_status   VARCHAR(20)     NOT NULL DEFAULT 'PROCESSED',
    received_at         TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    processed_at        TIMESTAMPTZ
);

CREATE INDEX idx_webhook_stripe_event_id ON webhook_events(stripe_event_id);
CREATE INDEX idx_webhook_type_received   ON webhook_events(event_type, received_at);

COMMENT ON COLUMN webhook_events.stripe_event_id IS
    'UNIQUE constraint — deduplicates Stripe retries. Stripe retries webhooks for 3 days.';

-- ─────────────────────────────────────────────────────────────────────────────
-- DISPUTES — Chargeback tracking
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE disputes (
    id                  VARCHAR(36)     PRIMARY KEY DEFAULT gen_random_uuid()::varchar,
    order_id            VARCHAR(36)     NOT NULL,
    stripe_dispute_id   VARCHAR(100)    NOT NULL UNIQUE,
    amount              DECIMAL(12,2)   NOT NULL,
    reason              VARCHAR(100),
    status              VARCHAR(20)     NOT NULL DEFAULT 'OPEN',
    evidence_due_date   TIMESTAMPTZ,
    submitted_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_disputes_status CHECK (status IN (
        'OPEN','EVIDENCE_SUBMITTED','UNDER_REVIEW','WON','LOST'
    ))
);

CREATE INDEX idx_disputes_order_id    ON disputes(order_id);
CREATE INDEX idx_disputes_stripe_id   ON disputes(stripe_dispute_id);
CREATE INDEX idx_disputes_status      ON disputes(status);

-- ─────────────────────────────────────────────────────────────────────────────
-- DUNNING CASES — Merchant fee retry state machine
--
-- States: GRACE_PERIOD → RETRY_1 → RETRY_2 → SUSPENDED → TERMINATED | RESOLVED
--
-- When SUSPENDED: publishes merchant.status.changed(SUSPENDED) → Identity Service
-- When TERMINATED: publishes merchant.terminated → full cleanup cascade
-- (Same events Identity Service emits on manual admin suspension — identical cascade)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE dunning_cases (
    id                  VARCHAR(36)     PRIMARY KEY DEFAULT gen_random_uuid()::varchar,
    merchant_id         VARCHAR(36)     NOT NULL,
    failed_payment_id   VARCHAR(36)     NOT NULL,
    payment_method_token VARCHAR(200),
    amount_due          DECIMAL(12,2),
    currency            VARCHAR(3)      NOT NULL DEFAULT 'MXN',
    current_stage       VARCHAR(20)     NOT NULL DEFAULT 'GRACE_PERIOD',
    retry_count         INT             NOT NULL DEFAULT 0,
    next_action_date    TIMESTAMPTZ,
    resolved_at         TIMESTAMPTZ,
    failure_started_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_dunning_stage CHECK (current_stage IN (
        'GRACE_PERIOD','RETRY_1','RETRY_2','SUSPENDED','TERMINATED','RESOLVED'
    ))
);

CREATE INDEX idx_dunning_merchant_id        ON dunning_cases(merchant_id);
CREATE INDEX idx_dunning_stage_next_action  ON dunning_cases(current_stage, next_action_date);


COMMENT ON TABLE dunning_cases IS
    'Merchant fee retry state machine. SUSPENDED publishes merchant.status.changed → Identity Service.';

-- ─────────────────────────────────────────────────────────────────────────────
-- PAYMENT OUTBOX — Transactional event bus
--
-- All Kafka events (payment.processed, payment.failed, refund.completed,
-- dispute.created, merchant.status.changed) written here transactionally
-- BEFORE returning from the charge/refund pipeline.
--
-- Outbox relay (500ms, SKIP LOCKED) forwards to Kafka.
-- Zero events lost between Stripe response and Kafka publish.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE payment_outbox (
    id           VARCHAR(36)     PRIMARY KEY DEFAULT gen_random_uuid()::varchar,
    event_type   VARCHAR(100)    NOT NULL,
    payload      JSONB           NOT NULL,
    aggregate_id VARCHAR(36),
    published    BOOLEAN         NOT NULL DEFAULT FALSE,
    published_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_outbox_unpublished ON payment_outbox(published, created_at)
    WHERE published = FALSE;

-- ─────────────────────────────────────────────────────────────────────────────
-- SHEDLOCK — distributed lock for dunning @Scheduled (daily tick)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE shedlock (
    name        VARCHAR(64)     NOT NULL,
    lock_until  TIMESTAMPTZ     NOT NULL,
    locked_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    locked_by   VARCHAR(255)    NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);

COMMENT ON TABLE shedlock IS
    'ShedLock: dunning tick runs daily at 2AM. Only 1 of N Pods executes per cycle.';

-- ─────────────────────────────────────────────────────────────────────────────
-- CLEANUP JOB — idempotency records expire after 7 days
-- ─────────────────────────────────────────────────────────────────────────────
-- Can be run as a periodic pg_cron job or Quarkus @Scheduled:
-- DELETE FROM payments_idempotency WHERE expires_at < NOW();
