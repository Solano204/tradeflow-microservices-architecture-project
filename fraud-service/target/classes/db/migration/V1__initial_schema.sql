-- V1__initial_schema.sql
-- Fraud Detection Service — PostgreSQL schema
-- MongoDB collections (fraud_vectors, fraud_rules, buyer_baselines, fraud_feedback)
-- are managed by Spring Data MongoDB (@Document annotations)
-- MongoDB Atlas Vector Search index must be created in Atlas UI:
--   Collection: fraud_vectors, Field: embedding
--   Type: knnVector, Dimensions: 1536, Similarity: cosine
--   Filter fields: label (string), buyer_id (string)

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ─────────────────────────────────────────────────────────────────────────────
-- FRAUD SCORES AUDIT — append-only compliance record
--
-- Every automated scoring decision recorded here.
-- GDPR Article 22 / LFPDPPP: automated decisions affecting users must be
-- explainable. buyer_context_snapshot provides the explanation.
-- Never updated — immutable compliance record.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE fraud_scores_audit (
    id                      VARCHAR(36)     PRIMARY KEY DEFAULT gen_random_uuid()::varchar,
    order_id                VARCHAR(36)     NOT NULL UNIQUE,
    buyer_id                VARCHAR(36)     NOT NULL,
    merchant_id             VARCHAR(36),
    score                   DOUBLE PRECISION NOT NULL,
    decision                VARCHAR(20)     NOT NULL,
    confidence              DOUBLE PRECISION,
    score_components        JSONB,
    rules_evaluated         JSONB,
    buyer_context_snapshot  JSONB,          -- snapshot at scoring time (explainability)
    model_version           VARCHAR(30),
    processing_time_ms      BIGINT,
    scored_at               TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_audit_decision CHECK (decision IN ('APPROVED','REVIEW','BLOCKED'))
);

CREATE INDEX idx_fraud_audit_order_id   ON fraud_scores_audit(order_id);
CREATE INDEX idx_fraud_audit_buyer_id   ON fraud_scores_audit(buyer_id);
CREATE INDEX idx_fraud_audit_decision   ON fraud_scores_audit(decision, scored_at);
CREATE INDEX idx_fraud_audit_scored_at  ON fraud_scores_audit(scored_at);
CREATE INDEX idx_fraud_audit_score      ON fraud_scores_audit(score) WHERE score > 0.65;

COMMENT ON TABLE fraud_scores_audit IS
    'Immutable compliance record. buyer_context_snapshot enables GDPR/LFPDPPP explainability.';
COMMENT ON COLUMN fraud_scores_audit.order_id IS 'UNIQUE — one audit row per order. Never overwritten.';

-- ─────────────────────────────────────────────────────────────────────────────
-- SHEDLOCK — rule cache refresh distributed lock
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE shedlock (
    name        VARCHAR(64)     NOT NULL,
    lock_until  TIMESTAMPTZ     NOT NULL,
    locked_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    locked_by   VARCHAR(255)    NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- SPRING BATCH — job repository tables
-- Created automatically by Spring Batch (spring.batch.jdbc.initialize-schema=always)
-- Listed here for documentation purposes.
-- ─────────────────────────────────────────────────────────────────────────────
-- spring_batch_job_instance
-- spring_batch_job_execution
-- spring_batch_job_execution_params
-- spring_batch_step_execution
-- spring_batch_step_execution_context
-- spring_batch_job_execution_context
-- (auto-created by Spring Batch — do NOT duplicate here)
