-- V1__initial_schema.sql
-- Product Catalog Service — complete schema
-- Flyway runs this automatically on startup

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ─────────────────────────────────────────────────────────────────────────────
-- CATEGORIES (Hierarchical tree — cached in Redis as full tree, TTL 1hr)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE categories (
    id                  VARCHAR(100)    PRIMARY KEY,
    name                VARCHAR(100)    NOT NULL,
    slug                VARCHAR(100)    NOT NULL UNIQUE,
    parent_id           VARCHAR(100)    REFERENCES categories(id),
    sort_order          INT             NOT NULL DEFAULT 0,
    required_attributes JSONB,
    active              BOOLEAN         NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_categories_parent ON categories(parent_id);
CREATE INDEX idx_categories_slug ON categories(slug);

COMMENT ON TABLE categories IS 'Category tree. Full tree cached in Redis (TTL 1hr). Invalidated on admin change.';
COMMENT ON COLUMN categories.required_attributes IS 'JSON array of required attribute names for products in this category';

-- Seed category data
INSERT INTO categories (id, name, slug, parent_id, sort_order, required_attributes) VALUES
('cat_electronics',               'Electronics',       'electronics',       NULL,                     1, NULL),
('cat_electronics_phones',        'Phones',            'phones',            'cat_electronics',         1, '["brand","os","storage_gb","connector_type"]'),
('cat_electronics_phones_android','Android',           'android',           'cat_electronics_phones',  1, '["brand","os","storage_gb","connector_type","ram_gb"]'),
('cat_electronics_phones_ios',    'iOS',               'ios',               'cat_electronics_phones',  2, '["brand","storage_gb","connector_type"]'),
('cat_electronics_laptops',       'Laptops',           'laptops',           'cat_electronics',         2, '["brand","ram_gb","storage_gb","processor","screen_size_in","connector_type"]'),
('cat_clothing',                  'Clothing',          'clothing',          NULL,                      2, NULL),
('cat_clothing_mens',             'Men''s Clothing',   'mens-clothing',     'cat_clothing',            1, '["brand","sizes","material","color"]'),
('cat_clothing_womens',           'Women''s Clothing', 'womens-clothing',   'cat_clothing',            2, '["brand","sizes","material","color"]'),
('cat_food',                      'Food & Beverages',  'food',              NULL,                      3, NULL),
('cat_food_fresh',                'Fresh Food',        'fresh-food',        'cat_food',                1, '["brand","weight_g","allergens","expiry_days"]');

-- ─────────────────────────────────────────────────────────────────────────────
-- PRODUCTS (Source of truth: price, status, stock)
-- MongoDB holds: description, attributes (varies by category), media, tags
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE products (
    id                  VARCHAR(36)     PRIMARY KEY,
    merchant_id         VARCHAR(36)     NOT NULL,
    title               VARCHAR(300)    NOT NULL,
    category_id         VARCHAR(100)    NOT NULL REFERENCES categories(id),
    price               DECIMAL(12,2)   NOT NULL,
    currency            VARCHAR(3)      NOT NULL DEFAULT 'MXN',  -- VARCHAR not CHAR — Hibernate expects varchar
    price_mode          VARCHAR(20)     NOT NULL DEFAULT 'FIXED',
    status              VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',
    stock_ref           VARCHAR(100),
    original_price      DECIMAL(12,2),
    promotional_price   DECIMAL(12,2),
    promotional_start   TIMESTAMPTZ,
    promotional_end     TIMESTAMPTZ,
    sales_volume        BIGINT          NOT NULL DEFAULT 0,
    delisted_at         TIMESTAMPTZ,
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_products_merchant        ON products(merchant_id);
CREATE INDEX idx_products_category_status ON products(category_id, status);
CREATE INDEX idx_products_status          ON products(status);
CREATE INDEX idx_products_category_price  ON products(category_id, price) WHERE status = 'ACTIVE';
CREATE INDEX idx_products_sales_volume    ON products(sales_volume DESC)   WHERE status = 'ACTIVE';
CREATE INDEX idx_products_promo_window    ON products(promotional_start, promotional_end)
    WHERE price_mode = 'PROMOTIONAL';

COMMENT ON TABLE products IS 'Source of truth for price and availability. MongoDB holds flexible attributes.';
COMMENT ON COLUMN products.version IS '@Version column — JPA optimistic lock. 409 Conflict on concurrent edit clash.';
COMMENT ON COLUMN products.sales_volume IS 'Used to rank top-1000 products for Redis cache pre-warming at startup.';

-- ─────────────────────────────────────────────────────────────────────────────
-- PRICING RULES (Tiered and promotional details — child of products)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE pricing_rules (
    id                  VARCHAR(36)     PRIMARY KEY DEFAULT gen_random_uuid()::varchar,
    product_id          VARCHAR(36)     NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    rule_type           VARCHAR(20)     NOT NULL,
    tier_min_qty        INT,
    tier_discount_pct   DECIMAL(5,2),
    promotional_start   TIMESTAMPTZ,
    promotional_end     TIMESTAMPTZ,
    promotional_price   DECIMAL(12,2),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pricing_rules_product ON pricing_rules(product_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- CATALOG OUTBOX (Transactional outbox — relay every 500ms, SKIP LOCKED)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE catalog_outbox (
    id              VARCHAR(36)     PRIMARY KEY DEFAULT gen_random_uuid()::varchar,
    event_type      VARCHAR(100)    NOT NULL,
    payload         JSONB           NOT NULL,
    aggregate_id    VARCHAR(36),
    published       BOOLEAN         NOT NULL DEFAULT FALSE,
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_catalog_outbox_unpublished ON catalog_outbox(published, created_at)
    WHERE published = FALSE;

-- ─────────────────────────────────────────────────────────────────────────────
-- SHEDLOCK (distributed lock for @Scheduled campaign tick — single Pod wins)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE shedlock (
    name        VARCHAR(64)     NOT NULL,
    lock_until  TIMESTAMPTZ     NOT NULL,
    locked_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    locked_by   VARCHAR(255)    NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);

COMMENT ON TABLE shedlock IS 'Campaign tick @Scheduled runs every 60s. ShedLock ensures only 1 of N Pods runs it.';

-- ─────────────────────────────────────────────────────────────────────────────
-- SEED PRODUCTS (dev/test only)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO products (id, merchant_id, title, category_id, price, currency, status, stock_ref, sales_volume)
VALUES
    ('prd_test0001', 'mrc_test0001', 'Samsung Galaxy S24 Ultra 256GB', 'cat_electronics_phones_android',
     18999.00, 'MXN', 'ACTIVE', 'inv_s24u_001', 1500),
    ('prd_test0002', 'mrc_test0001', 'MacBook Pro 14" M3', 'cat_electronics_laptops',
     34999.00, 'MXN', 'ACTIVE', 'inv_mbp14_001', 800)
ON CONFLICT (id) DO NOTHING;