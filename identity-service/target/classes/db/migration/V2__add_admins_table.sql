-- ============================================================
-- V2: Add admins table
-- ============================================================

CREATE TABLE IF NOT EXISTS admins (
    id              VARCHAR(36)  PRIMARY KEY,
    email           VARCHAR(255) NOT NULL UNIQUE,
    full_name       VARCHAR(120) NOT NULL,
    phone           VARCHAR(30),
    department      VARCHAR(50)  DEFAULT 'GENERAL',
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_login      TIMESTAMPTZ,
    created_by      VARCHAR(36),  -- references another admin
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT chk_admins_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'TERMINATED'))
);

CREATE INDEX idx_admins_email ON admins(email);
CREATE INDEX idx_admins_status ON admins(status);
CREATE INDEX idx_admins_created_by ON admins(created_by);

COMMENT ON TABLE admins IS 'Admin user profiles. Auth credentials stored in Auth Service.';
COMMENT ON COLUMN admins.created_by IS 'ID of the admin who created this admin (audit trail)';