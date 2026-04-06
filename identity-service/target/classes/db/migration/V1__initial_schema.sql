-- V1__initial_schema.sql
-- Identity & Merchant Service — complete schema
-- Flyway runs this automatically on startup

-- ─────────────────────────────────────────────────────────────────────────────
-- EXTENSIONS
-- ─────────────────────────────────────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ─────────────────────────────────────────────────────────────────────────────
-- BUYERS (PostgreSQL write store — source of truth for profiles)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE buyers (
    id              VARCHAR(36)     PRIMARY KEY,
    email           VARCHAR(255)    NOT NULL UNIQUE,
    full_name       VARCHAR(120)    NOT NULL,
    phone           VARCHAR(30),
    address         JSONB,
    locale          VARCHAR(10)     DEFAULT 'en_US',
    saved_payment_methods JSONB     DEFAULT '[]'::jsonb,
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_buyers_email  ON buyers(email);
CREATE INDEX idx_buyers_status ON buyers(status);

COMMENT ON TABLE buyers IS 'PostgreSQL write store for buyer profiles. MongoDB holds the denormalized read projection.';
COMMENT ON COLUMN buyers.address IS 'JSONB: {street, city, state, postal_code, country}';
COMMENT ON COLUMN buyers.saved_payment_methods IS 'JSONB array of Stripe payment method tokens';

-- ─────────────────────────────────────────────────────────────────────────────
-- MERCHANTS (source of truth for merchant state machine)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE merchants (
    id                      VARCHAR(36)     PRIMARY KEY,
    business_name           VARCHAR(200)    NOT NULL,
    business_type           VARCHAR(50)     NOT NULL,
    tax_id                  VARCHAR(50)     NOT NULL UNIQUE,
    contact_email           VARCHAR(255)    NOT NULL,
    contact_phone           VARCHAR(30),
    bank_account_encrypted  JSONB,          -- Encrypted at application level
    business_address        JSONB,
    status                  VARCHAR(30)     NOT NULL DEFAULT 'PENDING_KYC',
    kyc_status              VARCHAR(30)     NOT NULL DEFAULT 'NOT_STARTED',
    jumio_verification_id   VARCHAR(100),
    kyc_verified_at         TIMESTAMPTZ,
    active_since            TIMESTAMPTZ,
    suspension_reason       VARCHAR(500),
    suspended_at            TIMESTAMPTZ,
    suspended_by            VARCHAR(36),
    terminated_at           TIMESTAMPTZ,
    terminated_by           VARCHAR(36),
    version                 BIGINT          NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_merchants_status       ON merchants(status);
CREATE INDEX idx_merchants_contact_email ON merchants(contact_email);
CREATE INDEX idx_merchants_tax_id       ON merchants(tax_id);

COMMENT ON TABLE merchants IS 'Merchant state machine: PENDING_KYC → KYC_IN_PROGRESS → ACTIVE → SUSPENDED/TERMINATED';
COMMENT ON COLUMN merchants.bank_account_encrypted IS 'Encrypted CLABE + bank. Decryption key in KMS/Vault.';

-- ─────────────────────────────────────────────────────────────────────────────
-- MERCHANT CONTRACTS (legal record of ToS acceptance)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE merchant_contracts (
    id                  VARCHAR(36)     PRIMARY KEY DEFAULT gen_random_uuid()::varchar,
    merchant_id         VARCHAR(36)     NOT NULL REFERENCES merchants(id),
    contract_version    VARCHAR(20)     NOT NULL DEFAULT 'v2024.1',
    accepted_at         TIMESTAMPTZ     NOT NULL,
    ip_address          VARCHAR(50),
    user_agent          VARCHAR(500)
);

CREATE INDEX idx_contracts_merchant ON merchant_contracts(merchant_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- KYC DOCUMENTS (tracks uploads per merchant)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE kyc_documents (
    id                  VARCHAR(36)     PRIMARY KEY DEFAULT gen_random_uuid()::varchar,
    merchant_id         VARCHAR(36)     NOT NULL REFERENCES merchants(id),
    document_type       VARCHAR(50)     NOT NULL,  -- GOVERNMENT_ID / BUSINESS_REGISTRATION / PROOF_OF_ADDRESS
    s3_key              VARCHAR(500)    NOT NULL,
    s3_url              VARCHAR(1000)   NOT NULL,
    original_filename   VARCHAR(255),
    content_type        VARCHAR(100),
    uploaded_at         TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_kyc_docs_merchant ON kyc_documents(merchant_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- IDENTITY OUTBOX (transactional outbox pattern)
-- Relay job: SELECT FOR UPDATE SKIP LOCKED every 500ms
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE identity_outbox (
    id              VARCHAR(36)     PRIMARY KEY DEFAULT gen_random_uuid()::varchar,
    event_type      VARCHAR(100)    NOT NULL,
    payload         JSONB           NOT NULL,
    aggregate_id    VARCHAR(36),
    published       BOOLEAN         NOT NULL DEFAULT FALSE,
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- Partial index on unpublished rows — relay query only scans unpublished rows
CREATE INDEX idx_outbox_unpublished ON identity_outbox(published, created_at)
    WHERE published = FALSE;

CREATE INDEX idx_outbox_event_type ON identity_outbox(event_type);
CREATE INDEX idx_outbox_aggregate  ON identity_outbox(aggregate_id);

COMMENT ON TABLE identity_outbox IS 'Outbox for Kafka publishing. Relay: SELECT...FOR UPDATE SKIP LOCKED. Multi-pod safe.';

-- ─────────────────────────────────────────────────────────────────────────────
-- SHEDLOCK (distributed scheduling lock — prevents multi-pod duplicate jobs)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE shedlock (
    name            VARCHAR(64)     NOT NULL,
    lock_until      TIMESTAMPTZ     NOT NULL,
    locked_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    locked_by       VARCHAR(255)    NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);

COMMENT ON TABLE shedlock IS 'ShedLock distributed scheduling table. Used for any @Scheduled jobs beyond outbox relay.';

-- ─────────────────────────────────────────────────────────────────────────────
-- SEED DATA (optional dev/test bootstrap)
-- ─────────────────────────────────────────────────────────────────────────────
-- Test buyer for local development
INSERT INTO buyers (id, email, full_name, phone, address, locale, status)
VALUES (
    'usr_test0001',
    'buyer@tradeflow.io',
    'Test Buyer',
    '+52 55 1234 5678',
    '{"street":"Av. Insurgentes Sur 1234","city":"Ciudad de México","state":"CDMX","postal_code":"03100","country":"MX"}'::jsonb,
    'es_MX',
    'ACTIVE'
) ON CONFLICT (email) DO NOTHING;
