-- ============================================================
-- TradeFlow Auth Service — V1 Initial Schema
-- ============================================================

-- ============================================================
-- AUTH USERS
-- Core credential and MFA storage. Passwords are NEVER stored
-- in plain text — BCrypt hashed before insert.
-- ============================================================
CREATE TABLE IF NOT EXISTS auth_users (
    id                  VARCHAR(36)  NOT NULL,
    email               VARCHAR(255) NOT NULL,
    hashed_password     VARCHAR(255) NOT NULL,
    mfa_enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    mfa_secret          VARCHAR(255),               -- TOTP secret (encrypted at rest)
    roles               VARCHAR(255) NOT NULL DEFAULT 'BUYER',  -- comma-separated
    merchant_id         VARCHAR(36),                -- NULL for buyers, set for merchants
    status              VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    okta_subject        VARCHAR(255),               -- sub claim from Okta id_token (nullable)
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_login          TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_auth_users PRIMARY KEY (id),
    CONSTRAINT uq_auth_users_email UNIQUE (email),
    CONSTRAINT uq_auth_users_okta_subject UNIQUE (okta_subject),
    CONSTRAINT chk_auth_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'TERMINATED'))
);

CREATE INDEX idx_auth_users_email ON auth_users(email);
CREATE INDEX idx_auth_users_okta_subject ON auth_users(okta_subject) WHERE okta_subject IS NOT NULL;
CREATE INDEX idx_auth_users_status ON auth_users(status);

-- ============================================================
-- AUTH DEVICES
-- Tracks known devices per user. Used for:
-- 1. Device fingerprint validation on refresh
-- 2. Per-device session revocation (stolen phone scenario)
-- ============================================================
CREATE TABLE IF NOT EXISTS auth_devices (
    id                  VARCHAR(36)  NOT NULL,
    user_id             VARCHAR(36)  NOT NULL,
    device_fingerprint  VARCHAR(255) NOT NULL,      -- SHA256 hash of device characteristics
    device_name         VARCHAR(255),               -- User-defined friendly name (optional)
    last_seen           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    revoked             BOOLEAN      NOT NULL DEFAULT FALSE,
    revoked_at          TIMESTAMPTZ,
    revocation_reason   VARCHAR(255),

    CONSTRAINT pk_auth_devices PRIMARY KEY (id),
    CONSTRAINT uq_auth_devices_user_fp UNIQUE (user_id, device_fingerprint),
    CONSTRAINT fk_auth_devices_user FOREIGN KEY (user_id) REFERENCES auth_users(id) ON DELETE CASCADE
);

CREATE INDEX idx_auth_devices_user_id ON auth_devices(user_id);
CREATE INDEX idx_auth_devices_fingerprint ON auth_devices(device_fingerprint);
CREATE INDEX idx_auth_devices_user_fp ON auth_devices(user_id, device_fingerprint);
CREATE INDEX idx_auth_devices_revoked ON auth_devices(revoked) WHERE revoked = FALSE;

-- ============================================================
-- AUTH OUTBOX
-- Transactional Outbox Pattern for reliable Kafka publishing.
-- All events are written here atomically with business data.
-- The outbox relay job reads and publishes to Kafka.
-- ============================================================
CREATE TABLE IF NOT EXISTS auth_outbox (
    id                  BIGSERIAL    NOT NULL,
    event_type          VARCHAR(128) NOT NULL,       -- e.g. 'user.registered', 'device.revoked'
    aggregate_id        VARCHAR(36)  NOT NULL,       -- user_id or device_id
    payload             JSONB        NOT NULL,
    published           BOOLEAN      NOT NULL DEFAULT FALSE,
    published_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    retry_count         INT          NOT NULL DEFAULT 0,
    last_error          TEXT,

    CONSTRAINT pk_auth_outbox PRIMARY KEY (id)
);

CREATE INDEX idx_auth_outbox_unpublished ON auth_outbox(id) WHERE published = FALSE;
CREATE INDEX idx_auth_outbox_created_at ON auth_outbox(created_at);
-- Cleanup index for old published events
CREATE INDEX idx_auth_outbox_published_at ON auth_outbox(published_at) WHERE published = TRUE;

-- ============================================================
-- PKCE STATE STORE
-- Temporary storage for PKCE state during Okta OAuth flow.
-- State entries expire after 10 minutes (TTL managed by cleanup job).
-- ============================================================
CREATE TABLE IF NOT EXISTS pkce_state (
    state               VARCHAR(255) NOT NULL,
    code_verifier       VARCHAR(255) NOT NULL,
    nonce               VARCHAR(255),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMPTZ  NOT NULL DEFAULT (NOW() + INTERVAL '10 minutes'),

    CONSTRAINT pk_pkce_state PRIMARY KEY (state)
);

CREATE INDEX idx_pkce_state_expires_at ON pkce_state(expires_at);

-- ============================================================
-- SHEDLOCK
-- Distributed lock for @Scheduled jobs (prevents multi-pod
-- duplicate execution in Kubernetes).
-- ============================================================
CREATE TABLE IF NOT EXISTS shedlock (
    name                VARCHAR(64)  NOT NULL,
    lock_until          TIMESTAMP    NOT NULL,
    locked_at           TIMESTAMP    NOT NULL,
    locked_by           VARCHAR(255) NOT NULL,

    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);

-- ============================================================
-- AUDIT LOG
-- Immutable audit trail for security-sensitive operations.
-- Row-level INSERT only — no UPDATE or DELETE allowed.
-- ============================================================
CREATE TABLE IF NOT EXISTS auth_audit_log (
    id                  BIGSERIAL    NOT NULL,
    user_id             VARCHAR(36),
    event_type          VARCHAR(128) NOT NULL,
    ip_address          VARCHAR(64),
    user_agent          TEXT,
    device_fingerprint  VARCHAR(255),
    details             JSONB,
    occurred_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_auth_audit_log PRIMARY KEY (id)
);

CREATE INDEX idx_audit_log_user_id ON auth_audit_log(user_id);
CREATE INDEX idx_audit_log_event_type ON auth_audit_log(event_type);
CREATE INDEX idx_audit_log_occurred_at ON auth_audit_log(occurred_at);

-- ============================================================
-- INITIAL DATA — Create a default admin user for development
-- Password: 'admin123' (BCrypt hash — change in production!)
-- ============================================================
INSERT INTO auth_users (id, email, hashed_password, roles, status)
VALUES (
    'usr_admin_001',
    'admin@tradeflow.io',
    '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/Leen2QqhJV7/1y6Ri',
    'ADMIN',
    'ACTIVE'
) ON CONFLICT (email) DO NOTHING;
