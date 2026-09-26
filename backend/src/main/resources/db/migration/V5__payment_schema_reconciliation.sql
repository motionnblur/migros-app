-- V5: payment-schema completion and reconciliation.
--
-- Selected Flyway path (option 2 of the schema-integrity plan): one forward-only
-- completion/reconciliation migration that runs on EVERY database, instead of a
-- baseline trick or another guarded no-op recorded before Hibernate creates its
-- target tables.
--
-- Why this migration exists
-- -------------------------
-- Flyway runs before Hibernate. On a brand-new empty database V2/V3/V4 record
-- themselves but skip their bodies because the base tables do not exist yet;
-- Hibernate `ddl-auto=update` then creates the checkout/payment tables from JPA
-- annotations WITHOUT the Flyway-declared check constraints, currency guards,
-- recovery indexes, DB column defaults and ON DELETE CASCADE behaviour. The
-- fresh schema is therefore permanently weaker than an upgraded legacy schema.
--
-- What V5 does (idempotent on every path, never edits V1-V4 checksums)
-- --------------------------------------------------------------------
-- A. Completes the base (non-payment) tables when they are missing so that a
--    fresh database is fully usable without Hibernate creating tables. Column
--    definitions mirror what Hibernate 6 maps from the JPA entities (verified
--    against a Hibernate-created schema dump). Existing tables are only healed
--    with ADD COLUMN IF NOT EXISTS; column types and stored data are never
--    altered or coerced.
-- B. Creates the four payment tables with the exact canonical definitions when
--    they are missing (same predicates, defaults and index shapes as V2/V3/V4,
--    but with stable canonical foreign-key names).
-- C. Reconciles payment tables that already exist (V2/V3-created, or created by
--    Hibernate `ddl-auto=update` on databases that never saw V2/V3):
--      - validates existing rows and FAILS with an actionable message that
--        reports the offending row count instead of silently coercing amounts,
--        currencies, quantities or states;
--      - adds every canonical check/unique/foreign-key/index with its stable
--        explicit name;
--      - replaces redundant same-column artifacts (Hibernate/PG auto-named
--        foreign keys, duplicate uniques, Hibernate-generated `..._status_check`
--        duplicates) with the canonical ones so fresh and upgraded schemas are
--        name-identical. Only enforcement-identical duplicates on the same
--        columns referencing the same table are touched.
-- D. Self-verifies: raises if any canonical constraint or index is still
--    missing after reconciliation.
--
-- Canonical payment manifest owned by this migration
-- ---------------------------------------------------
-- Checks : chk_checkout_total_positive, chk_checkout_amount_minor_positive,
--          chk_checkout_currency (TRY-only), chk_checkout_status,
--          chk_checkout_item_quantity_positive,
--          chk_checkout_item_unit_price_non_negative,
--          chk_checkout_item_line_total_non_negative,
--          chk_payment_attempt_amount_positive, chk_payment_attempt_currency,
--          chk_payment_attempt_status, chk_stripe_event_status.
-- Uniques: uq_checkout_order_group, uq_checkout_item_product,
--          uq_payment_attempt_checkout, uq_payment_attempt_idempotency,
--          uq_payment_attempt_charge.
-- FKs    : fk_checkout_user, fk_checkout_item_checkout (ON DELETE CASCADE),
--          fk_checkout_item_product, fk_payment_attempt_checkout.
-- Indexes: uq_checkout_live_per_user (partial, one live checkout per user),
--          idx_checkout_user_status, idx_checkout_expires_at,
--          idx_checkout_item_checkout, idx_payment_attempt_status_updated,
--          idx_stripe_event_recovery, idx_stripe_event_lease.
--
-- Locking, deployment sequencing and rollback
-- -------------------------------------------
-- Every ADD CONSTRAINT / CREATE INDEX takes an ACCESS EXCLUSIVE lock on its
-- table while existing rows are scanned, and the whole migration runs in the
-- Flyway transaction (failure rolls everything back). Payment tables are
-- small; nevertheless rehearse on a production-shaped copy during low traffic,
-- back up first, and only switch the application to `ddl-auto=validate` after
-- V5 has applied cleanly. If V5 reports corrupt rows, stop: correct the data
-- manually, never delete or rewrite financial records automatically. No
-- lock_timeout is forced here so a DBA can set statement/lock timeouts
-- externally; without one a contended lock waits rather than failing.
--
-- Which migrations execute (proven by PaymentSchemaEquivalencePostgresTest)
-- ------------------------------------------------------------------------
-- Empty database      : V1 (guarded no-op), V2 (guarded no-op),
--                       V3 (guarded no-op), V4 (guarded no-op), V5 builds all.
-- Legacy pre-V1       : V1 converts money, V2/V3/V4 build, V5 reconciles.
-- Hibernate-built     : V2/V3/V4 no-op on existing tables, V5 reconciles.
-- Database at V3/V4   : V5 reconciles forward. Re-migrate is a no-op.

-- ======================================================================
-- A. Base tables: complete when missing, heal when minimal.
-- Column definitions mirror the Hibernate 6 JPA mappings so that
-- `ddl-auto=validate` passes on a Flyway-built fresh database.
-- ======================================================================

CREATE TABLE IF NOT EXISTS admin_entity (
    admin_entity_id BIGSERIAL PRIMARY KEY,
    admin_name VARCHAR(255),
    admin_password VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS category_entity (
    category_entity_id BIGSERIAL PRIMARY KEY,
    category_id INTEGER NOT NULL,
    category_name VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS user_entity (
    user_entity_id BIGSERIAL PRIMARY KEY,
    banned BOOLEAN,
    products_ids_in_cart BIGINT[],
    user_address VARCHAR(255),
    user_address2 VARCHAR(255),
    user_country VARCHAR(255),
    user_last_name VARCHAR(255),
    user_mail VARCHAR(255),
    user_name VARCHAR(255),
    user_password VARCHAR(255),
    user_postal_code VARCHAR(255),
    user_town VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS product_entity (
    product_entity_id BIGSERIAL PRIMARY KEY,
    product_count INTEGER NOT NULL,
    product_description VARCHAR(255) NOT NULL,
    product_discount NUMERIC(19, 2) NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    product_price NUMERIC(19, 2) NOT NULL,
    subcategory_name VARCHAR(255) NOT NULL,
    admin_entity_id BIGINT,
    category_entity_id BIGINT
);

CREATE TABLE IF NOT EXISTS order_group_entity (
    order_group_entity_id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMP WITHOUT TIME ZONE,
    status VARCHAR(255),
    user_id BIGINT,
    user_entity_id BIGINT
);

CREATE TABLE IF NOT EXISTS order_entity (
    order_entity_id BIGSERIAL PRIMARY KEY,
    count INTEGER,
    item_id BIGINT,
    price NUMERIC(19, 2),
    status VARCHAR(255),
    total_price NUMERIC(19, 2),
    user_id BIGINT,
    order_group_entity_id BIGINT,
    user_entity_id BIGINT
);

CREATE TABLE IF NOT EXISTS support_messages (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    edited_at TIMESTAMP WITHOUT TIME ZONE,
    external_message_id VARCHAR(255) UNIQUE,
    message VARCHAR(2000) NOT NULL,
    sender VARCHAR(255) NOT NULL,
    user_mail VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS pending_signup_entity (
    token VARCHAR(64) PRIMARY KEY,
    expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    user_mail VARCHAR(255) NOT NULL,
    user_password VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS product_image_entity (
    product_image_entity_id BIGSERIAL PRIMARY KEY,
    image_path VARCHAR(255),
    product_entity_id BIGINT
);

CREATE TABLE IF NOT EXISTS product_description_entity (
    product_description_entity_id BIGSERIAL PRIMARY KEY,
    description_tab_content VARCHAR(255),
    description_tab_name VARCHAR(255),
    product_entity_id BIGINT
);

-- Heal minimal/legacy base tables: add mapped columns that are missing.
-- Nullable adds never break existing rows; Hibernate validation only requires
-- presence with a compatible type, so nullability is intentionally untouched.
ALTER TABLE admin_entity ADD COLUMN IF NOT EXISTS admin_name VARCHAR(255);
ALTER TABLE admin_entity ADD COLUMN IF NOT EXISTS admin_password VARCHAR(255);

ALTER TABLE category_entity ADD COLUMN IF NOT EXISTS category_id INTEGER;
ALTER TABLE category_entity ADD COLUMN IF NOT EXISTS category_name VARCHAR(255);

ALTER TABLE user_entity ADD COLUMN IF NOT EXISTS banned BOOLEAN;
ALTER TABLE user_entity ADD COLUMN IF NOT EXISTS products_ids_in_cart BIGINT[];
ALTER TABLE user_entity ADD COLUMN IF NOT EXISTS user_address VARCHAR(255);
ALTER TABLE user_entity ADD COLUMN IF NOT EXISTS user_address2 VARCHAR(255);
ALTER TABLE user_entity ADD COLUMN IF NOT EXISTS user_country VARCHAR(255);
ALTER TABLE user_entity ADD COLUMN IF NOT EXISTS user_last_name VARCHAR(255);
ALTER TABLE user_entity ADD COLUMN IF NOT EXISTS user_mail VARCHAR(255);
ALTER TABLE user_entity ADD COLUMN IF NOT EXISTS user_name VARCHAR(255);
ALTER TABLE user_entity ADD COLUMN IF NOT EXISTS user_password VARCHAR(255);
ALTER TABLE user_entity ADD COLUMN IF NOT EXISTS user_postal_code VARCHAR(255);
ALTER TABLE user_entity ADD COLUMN IF NOT EXISTS user_town VARCHAR(255);

ALTER TABLE product_entity ADD COLUMN IF NOT EXISTS product_count INTEGER;
ALTER TABLE product_entity ADD COLUMN IF NOT EXISTS product_description VARCHAR(255);
ALTER TABLE product_entity ADD COLUMN IF NOT EXISTS product_discount NUMERIC(19, 2);
ALTER TABLE product_entity ADD COLUMN IF NOT EXISTS product_name VARCHAR(255);
ALTER TABLE product_entity ADD COLUMN IF NOT EXISTS product_price NUMERIC(19, 2);
ALTER TABLE product_entity ADD COLUMN IF NOT EXISTS subcategory_name VARCHAR(255);
ALTER TABLE product_entity ADD COLUMN IF NOT EXISTS admin_entity_id BIGINT;
ALTER TABLE product_entity ADD COLUMN IF NOT EXISTS category_entity_id BIGINT;

ALTER TABLE order_group_entity ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE order_group_entity ADD COLUMN IF NOT EXISTS status VARCHAR(255);
ALTER TABLE order_group_entity ADD COLUMN IF NOT EXISTS user_id BIGINT;
ALTER TABLE order_group_entity ADD COLUMN IF NOT EXISTS user_entity_id BIGINT;

ALTER TABLE order_entity ADD COLUMN IF NOT EXISTS count INTEGER;
ALTER TABLE order_entity ADD COLUMN IF NOT EXISTS item_id BIGINT;
ALTER TABLE order_entity ADD COLUMN IF NOT EXISTS price NUMERIC(19, 2);
ALTER TABLE order_entity ADD COLUMN IF NOT EXISTS status VARCHAR(255);
ALTER TABLE order_entity ADD COLUMN IF NOT EXISTS total_price NUMERIC(19, 2);
ALTER TABLE order_entity ADD COLUMN IF NOT EXISTS user_id BIGINT;
ALTER TABLE order_entity ADD COLUMN IF NOT EXISTS order_group_entity_id BIGINT;
ALTER TABLE order_entity ADD COLUMN IF NOT EXISTS user_entity_id BIGINT;

ALTER TABLE support_messages ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE support_messages ADD COLUMN IF NOT EXISTS edited_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE support_messages ADD COLUMN IF NOT EXISTS external_message_id VARCHAR(255);
ALTER TABLE support_messages ADD COLUMN IF NOT EXISTS message VARCHAR(2000);
ALTER TABLE support_messages ADD COLUMN IF NOT EXISTS sender VARCHAR(255);
ALTER TABLE support_messages ADD COLUMN IF NOT EXISTS user_mail VARCHAR(255);

ALTER TABLE pending_signup_entity ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE pending_signup_entity ADD COLUMN IF NOT EXISTS user_mail VARCHAR(255);
ALTER TABLE pending_signup_entity ADD COLUMN IF NOT EXISTS user_password VARCHAR(255);

ALTER TABLE product_image_entity ADD COLUMN IF NOT EXISTS image_path VARCHAR(255);
ALTER TABLE product_image_entity ADD COLUMN IF NOT EXISTS product_entity_id BIGINT;

ALTER TABLE product_description_entity ADD COLUMN IF NOT EXISTS description_tab_content VARCHAR(255);
ALTER TABLE product_description_entity ADD COLUMN IF NOT EXISTS description_tab_name VARCHAR(255);
ALTER TABLE product_description_entity ADD COLUMN IF NOT EXISTS product_entity_id BIGINT;

-- Base-table foreign keys: present for faithfulness, added only when no
-- foreign key exists at all on the same column (names stay untouched).
DO $$
DECLARE
    v_attnum SMALLINT;
BEGIN
    SELECT attnum INTO v_attnum FROM pg_attribute
    WHERE attrelid = 'order_entity'::regclass AND attname = 'user_entity_id';
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'order_entity'::regclass
                   AND contype = 'f' AND conkey = ARRAY[v_attnum]) THEN
        ALTER TABLE order_entity ADD FOREIGN KEY (user_entity_id) REFERENCES user_entity (user_entity_id);
    END IF;

    SELECT attnum INTO v_attnum FROM pg_attribute
    WHERE attrelid = 'order_entity'::regclass AND attname = 'order_group_entity_id';
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'order_entity'::regclass
                   AND contype = 'f' AND conkey = ARRAY[v_attnum]) THEN
        ALTER TABLE order_entity ADD FOREIGN KEY (order_group_entity_id)
            REFERENCES order_group_entity (order_group_entity_id);
    END IF;

    SELECT attnum INTO v_attnum FROM pg_attribute
    WHERE attrelid = 'order_group_entity'::regclass AND attname = 'user_entity_id';
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'order_group_entity'::regclass
                   AND contype = 'f' AND conkey = ARRAY[v_attnum]) THEN
        ALTER TABLE order_group_entity ADD FOREIGN KEY (user_entity_id)
            REFERENCES user_entity (user_entity_id);
    END IF;

    SELECT attnum INTO v_attnum FROM pg_attribute
    WHERE attrelid = 'product_entity'::regclass AND attname = 'admin_entity_id';
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'product_entity'::regclass
                   AND contype = 'f' AND conkey = ARRAY[v_attnum]) THEN
        ALTER TABLE product_entity ADD FOREIGN KEY (admin_entity_id)
            REFERENCES admin_entity (admin_entity_id);
    END IF;

    SELECT attnum INTO v_attnum FROM pg_attribute
    WHERE attrelid = 'product_entity'::regclass AND attname = 'category_entity_id';
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'product_entity'::regclass
                   AND contype = 'f' AND conkey = ARRAY[v_attnum]) THEN
        ALTER TABLE product_entity ADD FOREIGN KEY (category_entity_id)
            REFERENCES category_entity (category_entity_id);
    END IF;

    SELECT attnum INTO v_attnum FROM pg_attribute
    WHERE attrelid = 'product_image_entity'::regclass AND attname = 'product_entity_id';
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'product_image_entity'::regclass
                   AND contype = 'f' AND conkey = ARRAY[v_attnum]) THEN
        ALTER TABLE product_image_entity ADD FOREIGN KEY (product_entity_id)
            REFERENCES product_entity (product_entity_id);
    END IF;

    SELECT attnum INTO v_attnum FROM pg_attribute
    WHERE attrelid = 'product_description_entity'::regclass AND attname = 'product_entity_id';
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'product_description_entity'::regclass
                   AND contype = 'f' AND conkey = ARRAY[v_attnum]) THEN
        ALTER TABLE product_description_entity ADD FOREIGN KEY (product_entity_id)
            REFERENCES product_entity (product_entity_id);
    END IF;
END $$;

-- ======================================================================
-- B. Payment tables: create when missing with the canonical definitions.
-- Same predicates/defaults/index shapes as V2/V3/V4, but foreign keys use
-- the stable canonical names from the manifest above.
-- ======================================================================

CREATE TABLE IF NOT EXISTS checkout_entity (
    checkout_id UUID PRIMARY KEY,
    user_entity_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    total_amount NUMERIC(19, 2) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    order_group_entity_id BIGINT,
    stripe_charge_id VARCHAR(255),
    CONSTRAINT chk_checkout_total_positive CHECK (total_amount > 0),
    CONSTRAINT chk_checkout_amount_minor_positive CHECK (amount_minor > 0),
    CONSTRAINT chk_checkout_currency CHECK (currency = 'try'),
    CONSTRAINT chk_checkout_status CHECK (status IN
        ('PREPARED', 'PAYMENT_PROCESSING', 'PAID', 'CONSUMED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT uq_checkout_order_group UNIQUE (order_group_entity_id),
    CONSTRAINT fk_checkout_user FOREIGN KEY (user_entity_id) REFERENCES user_entity (user_entity_id)
);

CREATE TABLE IF NOT EXISTS checkout_item_entity (
    checkout_item_entity_id BIGSERIAL PRIMARY KEY,
    checkout_id UUID NOT NULL,
    product_entity_id BIGINT NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price NUMERIC(19, 2) NOT NULL,
    line_total NUMERIC(19, 2) NOT NULL,
    CONSTRAINT chk_checkout_item_quantity_positive CHECK (quantity > 0),
    CONSTRAINT chk_checkout_item_unit_price_non_negative CHECK (unit_price >= 0),
    CONSTRAINT chk_checkout_item_line_total_non_negative CHECK (line_total >= 0),
    CONSTRAINT uq_checkout_item_product UNIQUE (checkout_id, product_entity_id),
    CONSTRAINT fk_checkout_item_checkout FOREIGN KEY (checkout_id)
        REFERENCES checkout_entity (checkout_id) ON DELETE CASCADE,
    CONSTRAINT fk_checkout_item_product FOREIGN KEY (product_entity_id)
        REFERENCES product_entity (product_entity_id)
);

CREATE TABLE IF NOT EXISTS payment_attempt_entity (
    attempt_id UUID PRIMARY KEY,
    checkout_id UUID NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    stripe_charge_id VARCHAR(255),
    provider_status VARCHAR(64),
    error_code VARCHAR(64),
    refund_id VARCHAR(255),
    lease_owner VARCHAR(64),
    lease_expires_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_payment_attempt_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT chk_payment_attempt_currency CHECK (currency = 'try'),
    CONSTRAINT chk_payment_attempt_status CHECK (status IN
        ('CREATED', 'PROCESSING', 'CHARGE_SUCCEEDED', 'ORDER_FINALIZED',
         'FAILED_FINAL', 'REFUND_PENDING', 'REFUNDED', 'MANUAL_REVIEW')),
    CONSTRAINT uq_payment_attempt_checkout UNIQUE (checkout_id),
    CONSTRAINT uq_payment_attempt_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uq_payment_attempt_charge UNIQUE (stripe_charge_id),
    CONSTRAINT fk_payment_attempt_checkout FOREIGN KEY (checkout_id)
        REFERENCES checkout_entity (checkout_id)
);

CREATE TABLE IF NOT EXISTS stripe_event_entity (
    event_id VARCHAR(255) PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    received_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITHOUT TIME ZONE,
    status VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
    payload TEXT,
    payload_hash VARCHAR(64) NOT NULL DEFAULT '',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(64),
    lease_owner VARCHAR(64),
    lease_expires_at TIMESTAMP WITHOUT TIME ZONE,
    processing_started_at TIMESTAMP WITHOUT TIME ZONE,
    next_attempt_at TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT chk_stripe_event_status CHECK (status IN
        ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED', 'MANUAL_REVIEW'))
);

-- ======================================================================
-- C. Reconciliation of pre-existing payment tables.
-- Each block: validate rows with an actionable error, add the canonical
-- artifact when missing, then drop redundant same-column duplicates so all
-- paths converge to identical names.
-- ======================================================================

-- ---- checkout_entity checks ----
DO $$
DECLARE
    v_violations BIGINT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'checkout_entity'::regclass
                   AND conname = 'chk_checkout_total_positive') THEN
        SELECT count(*) INTO v_violations FROM checkout_entity WHERE NOT (total_amount > 0);
        IF v_violations > 0 THEN
            RAISE EXCEPTION 'V5 refuses to add chk_checkout_total_positive: % row(s) in checkout_entity violate total_amount > 0. Correct the data and re-run the migration; financial rows are never coerced.',
                v_violations;
        END IF;
        ALTER TABLE checkout_entity ADD CONSTRAINT chk_checkout_total_positive CHECK (total_amount > 0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'checkout_entity'::regclass
                   AND conname = 'chk_checkout_amount_minor_positive') THEN
        SELECT count(*) INTO v_violations FROM checkout_entity WHERE NOT (amount_minor > 0);
        IF v_violations > 0 THEN
            RAISE EXCEPTION 'V5 refuses to add chk_checkout_amount_minor_positive: % row(s) in checkout_entity violate amount_minor > 0. Correct the data and re-run the migration; financial rows are never coerced.',
                v_violations;
        END IF;
        ALTER TABLE checkout_entity ADD CONSTRAINT chk_checkout_amount_minor_positive CHECK (amount_minor > 0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'checkout_entity'::regclass
                   AND conname = 'chk_checkout_currency') THEN
        SELECT count(*) INTO v_violations FROM checkout_entity WHERE NOT (currency = 'try');
        IF v_violations > 0 THEN
            RAISE EXCEPTION 'V5 refuses to add chk_checkout_currency: % row(s) in checkout_entity have a currency other than TRY. Correct the data and re-run the migration; financial rows are never coerced.',
                v_violations;
        END IF;
        ALTER TABLE checkout_entity ADD CONSTRAINT chk_checkout_currency CHECK (currency = 'try');
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'checkout_entity'::regclass
                   AND conname = 'chk_checkout_status') THEN
        SELECT count(*) INTO v_violations FROM checkout_entity WHERE status NOT IN
            ('PREPARED', 'PAYMENT_PROCESSING', 'PAID', 'CONSUMED', 'CANCELLED', 'EXPIRED');
        IF v_violations > 0 THEN
            RAISE EXCEPTION 'V5 refuses to add chk_checkout_status: % row(s) in checkout_entity carry an illegal status. Correct the data and re-run the migration; payment states are never rewritten automatically.',
                v_violations;
        END IF;
        ALTER TABLE checkout_entity ADD CONSTRAINT chk_checkout_status CHECK (status IN
            ('PREPARED', 'PAYMENT_PROCESSING', 'PAID', 'CONSUMED', 'CANCELLED', 'EXPIRED'));
    END IF;

    -- The Hibernate-generated enum duplicate (same name PostgreSQL would
    -- auto-generate) is redundant once the canonical check is enforced.
    ALTER TABLE checkout_entity DROP CONSTRAINT IF EXISTS checkout_entity_status_check;
END $$;

-- ---- checkout_entity unique + foreign key + defaults ----
DO $$
DECLARE
    r RECORD;
    v_attnum SMALLINT;
    v_key SMALLINT[];
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'checkout_entity'::regclass
                   AND conname = 'uq_checkout_order_group') THEN
        ALTER TABLE checkout_entity ADD CONSTRAINT uq_checkout_order_group UNIQUE (order_group_entity_id);
    END IF;
    SELECT conkey INTO v_key FROM pg_constraint
    WHERE conrelid = 'checkout_entity'::regclass AND conname = 'uq_checkout_order_group';
    FOR r IN SELECT conname FROM pg_constraint WHERE conrelid = 'checkout_entity'::regclass
             AND contype = 'u' AND conname <> 'uq_checkout_order_group' AND conkey = v_key
    LOOP
        EXECUTE format('ALTER TABLE checkout_entity DROP CONSTRAINT %I', r.conname);
    END LOOP;

    -- Replace any non-canonical FK on (user_entity_id -> user_entity) with the
    -- canonical name. Same columns, same referenced table: no valid data or
    -- enforcement is lost, only the name is normalized.
    SELECT attnum INTO v_attnum FROM pg_attribute
    WHERE attrelid = 'checkout_entity'::regclass AND attname = 'user_entity_id';
    FOR r IN SELECT conname FROM pg_constraint WHERE conrelid = 'checkout_entity'::regclass
             AND contype = 'f' AND conname <> 'fk_checkout_user'
             AND confrelid = 'user_entity'::regclass AND conkey = ARRAY[v_attnum]
    LOOP
        EXECUTE format('ALTER TABLE checkout_entity DROP CONSTRAINT %I', r.conname);
    END LOOP;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'checkout_entity'::regclass
                   AND conname = 'fk_checkout_user') THEN
        IF EXISTS (SELECT 1 FROM checkout_entity c LEFT JOIN user_entity u
                   ON u.user_entity_id = c.user_entity_id WHERE c.user_entity_id IS NOT NULL
                   AND u.user_entity_id IS NULL) THEN
            RAISE EXCEPTION 'V5 refuses to add fk_checkout_user: % orphan checkout row(s) reference a missing user_entity_id. Correct the data and re-run the migration.',
                (SELECT count(*) FROM checkout_entity c LEFT JOIN user_entity u
                 ON u.user_entity_id = c.user_entity_id WHERE c.user_entity_id IS NOT NULL
                 AND u.user_entity_id IS NULL);
        END IF;
        ALTER TABLE checkout_entity ADD CONSTRAINT fk_checkout_user
            FOREIGN KEY (user_entity_id) REFERENCES user_entity (user_entity_id);
    END IF;

    ALTER TABLE checkout_entity ALTER COLUMN version SET DEFAULT 0;
END $$;

-- ---- checkout_entity indexes (idempotent by name) ----
CREATE INDEX IF NOT EXISTS idx_checkout_user_status ON checkout_entity (user_entity_id, status);
CREATE INDEX IF NOT EXISTS idx_checkout_expires_at ON checkout_entity (expires_at);
CREATE UNIQUE INDEX IF NOT EXISTS uq_checkout_live_per_user
    ON checkout_entity (user_entity_id)
    WHERE status IN ('PREPARED', 'PAYMENT_PROCESSING');

-- ---- checkout_item_entity checks ----
DO $$
DECLARE
    v_violations BIGINT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'checkout_item_entity'::regclass
                   AND conname = 'chk_checkout_item_quantity_positive') THEN
        SELECT count(*) INTO v_violations FROM checkout_item_entity WHERE NOT (quantity > 0);
        IF v_violations > 0 THEN
            RAISE EXCEPTION 'V5 refuses to add chk_checkout_item_quantity_positive: % row(s) in checkout_item_entity violate quantity > 0. Correct the data and re-run the migration.',
                v_violations;
        END IF;
        ALTER TABLE checkout_item_entity
            ADD CONSTRAINT chk_checkout_item_quantity_positive CHECK (quantity > 0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'checkout_item_entity'::regclass
                   AND conname = 'chk_checkout_item_unit_price_non_negative') THEN
        SELECT count(*) INTO v_violations FROM checkout_item_entity WHERE NOT (unit_price >= 0);
        IF v_violations > 0 THEN
            RAISE EXCEPTION 'V5 refuses to add chk_checkout_item_unit_price_non_negative: % row(s) in checkout_item_entity violate unit_price >= 0. Correct the data and re-run the migration.',
                v_violations;
        END IF;
        ALTER TABLE checkout_item_entity
            ADD CONSTRAINT chk_checkout_item_unit_price_non_negative CHECK (unit_price >= 0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'checkout_item_entity'::regclass
                   AND conname = 'chk_checkout_item_line_total_non_negative') THEN
        SELECT count(*) INTO v_violations FROM checkout_item_entity WHERE NOT (line_total >= 0);
        IF v_violations > 0 THEN
            RAISE EXCEPTION 'V5 refuses to add chk_checkout_item_line_total_non_negative: % row(s) in checkout_item_entity violate line_total >= 0. Correct the data and re-run the migration.',
                v_violations;
        END IF;
        ALTER TABLE checkout_item_entity
            ADD CONSTRAINT chk_checkout_item_line_total_non_negative CHECK (line_total >= 0);
    END IF;
END $$;

-- ---- checkout_item_entity uniques, foreign keys, index ----
DO $$
DECLARE
    r RECORD;
    v_attnum SMALLINT;
    v_first SMALLINT;
    v_second SMALLINT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'checkout_item_entity'::regclass
                   AND conname = 'uq_checkout_item_product') THEN
        ALTER TABLE checkout_item_entity
            ADD CONSTRAINT uq_checkout_item_product UNIQUE (checkout_id, product_entity_id);
    END IF;
    SELECT attnum INTO v_first FROM pg_attribute
    WHERE attrelid = 'checkout_item_entity'::regclass AND attname = 'checkout_id';
    SELECT attnum INTO v_second FROM pg_attribute
    WHERE attrelid = 'checkout_item_entity'::regclass AND attname = 'product_entity_id';
    FOR r IN SELECT conname FROM pg_constraint WHERE conrelid = 'checkout_item_entity'::regclass
             AND contype = 'u' AND conname <> 'uq_checkout_item_product'
             AND (conkey = ARRAY[v_first, v_second] OR conkey = ARRAY[v_second, v_first])
    LOOP
        EXECUTE format('ALTER TABLE checkout_item_entity DROP CONSTRAINT %I', r.conname);
    END LOOP;

    -- Canonical delete behavior is ON DELETE CASCADE (V2): checkout items die
    -- with their checkout. Any non-canonical FK on this column pair is
    -- replaced so fresh and upgraded schemas behave identically.
    FOR r IN SELECT conname FROM pg_constraint WHERE conrelid = 'checkout_item_entity'::regclass
             AND contype = 'f' AND conname <> 'fk_checkout_item_checkout'
             AND confrelid = 'checkout_entity'::regclass AND conkey = ARRAY[v_first]
    LOOP
        EXECUTE format('ALTER TABLE checkout_item_entity DROP CONSTRAINT %I', r.conname);
    END LOOP;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'checkout_item_entity'::regclass
                   AND conname = 'fk_checkout_item_checkout') THEN
        IF EXISTS (SELECT 1 FROM checkout_item_entity i LEFT JOIN checkout_entity c
                   ON c.checkout_id = i.checkout_id WHERE c.checkout_id IS NULL) THEN
            RAISE EXCEPTION 'V5 refuses to add fk_checkout_item_checkout: % orphan checkout_item row(s) reference a missing checkout_id. Correct the data and re-run the migration.',
                (SELECT count(*) FROM checkout_item_entity i LEFT JOIN checkout_entity c
                 ON c.checkout_id = i.checkout_id WHERE c.checkout_id IS NULL);
        END IF;
        ALTER TABLE checkout_item_entity ADD CONSTRAINT fk_checkout_item_checkout
            FOREIGN KEY (checkout_id) REFERENCES checkout_entity (checkout_id) ON DELETE CASCADE;
    END IF;

    FOR r IN SELECT conname FROM pg_constraint WHERE conrelid = 'checkout_item_entity'::regclass
             AND contype = 'f' AND conname <> 'fk_checkout_item_product'
             AND confrelid = 'product_entity'::regclass AND conkey = ARRAY[v_second]
    LOOP
        EXECUTE format('ALTER TABLE checkout_item_entity DROP CONSTRAINT %I', r.conname);
    END LOOP;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'checkout_item_entity'::regclass
                   AND conname = 'fk_checkout_item_product') THEN
        IF EXISTS (SELECT 1 FROM checkout_item_entity i LEFT JOIN product_entity p
                   ON p.product_entity_id = i.product_entity_id WHERE p.product_entity_id IS NULL) THEN
            RAISE EXCEPTION 'V5 refuses to add fk_checkout_item_product: % orphan checkout_item row(s) reference a missing product_entity_id. Correct the data and re-run the migration.',
                (SELECT count(*) FROM checkout_item_entity i LEFT JOIN product_entity p
                 ON p.product_entity_id = i.product_entity_id WHERE p.product_entity_id IS NULL);
        END IF;
        ALTER TABLE checkout_item_entity ADD CONSTRAINT fk_checkout_item_product
            FOREIGN KEY (product_entity_id) REFERENCES product_entity (product_entity_id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_checkout_item_checkout ON checkout_item_entity (checkout_id);

-- ---- payment_attempt_entity checks ----
DO $$
DECLARE
    v_violations BIGINT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'payment_attempt_entity'::regclass
                   AND conname = 'chk_payment_attempt_amount_positive') THEN
        SELECT count(*) INTO v_violations FROM payment_attempt_entity WHERE NOT (amount_minor > 0);
        IF v_violations > 0 THEN
            RAISE EXCEPTION 'V5 refuses to add chk_payment_attempt_amount_positive: % row(s) in payment_attempt_entity violate amount_minor > 0. Correct the data and re-run the migration; financial rows are never coerced.',
                v_violations;
        END IF;
        ALTER TABLE payment_attempt_entity
            ADD CONSTRAINT chk_payment_attempt_amount_positive CHECK (amount_minor > 0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'payment_attempt_entity'::regclass
                   AND conname = 'chk_payment_attempt_currency') THEN
        SELECT count(*) INTO v_violations FROM payment_attempt_entity WHERE NOT (currency = 'try');
        IF v_violations > 0 THEN
            RAISE EXCEPTION 'V5 refuses to add chk_payment_attempt_currency: % row(s) in payment_attempt_entity have a currency other than TRY. Correct the data and re-run the migration; financial rows are never coerced.',
                v_violations;
        END IF;
        ALTER TABLE payment_attempt_entity
            ADD CONSTRAINT chk_payment_attempt_currency CHECK (currency = 'try');
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'payment_attempt_entity'::regclass
                   AND conname = 'chk_payment_attempt_status') THEN
        SELECT count(*) INTO v_violations FROM payment_attempt_entity WHERE status NOT IN
            ('CREATED', 'PROCESSING', 'CHARGE_SUCCEEDED', 'ORDER_FINALIZED',
             'FAILED_FINAL', 'REFUND_PENDING', 'REFUNDED', 'MANUAL_REVIEW');
        IF v_violations > 0 THEN
            RAISE EXCEPTION 'V5 refuses to add chk_payment_attempt_status: % row(s) in payment_attempt_entity carry an illegal status. Correct the data and re-run the migration; payment states are never rewritten automatically.',
                v_violations;
        END IF;
        ALTER TABLE payment_attempt_entity ADD CONSTRAINT chk_payment_attempt_status CHECK (status IN
            ('CREATED', 'PROCESSING', 'CHARGE_SUCCEEDED', 'ORDER_FINALIZED',
             'FAILED_FINAL', 'REFUND_PENDING', 'REFUNDED', 'MANUAL_REVIEW'));
    END IF;

    ALTER TABLE payment_attempt_entity DROP CONSTRAINT IF EXISTS payment_attempt_entity_status_check;
END $$;

-- ---- payment_attempt_entity uniques, foreign key, defaults, index ----
DO $$
DECLARE
    r RECORD;
    v_attnum SMALLINT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'payment_attempt_entity'::regclass
                   AND conname = 'uq_payment_attempt_checkout') THEN
        ALTER TABLE payment_attempt_entity
            ADD CONSTRAINT uq_payment_attempt_checkout UNIQUE (checkout_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'payment_attempt_entity'::regclass
                   AND conname = 'uq_payment_attempt_idempotency') THEN
        ALTER TABLE payment_attempt_entity
            ADD CONSTRAINT uq_payment_attempt_idempotency UNIQUE (idempotency_key);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'payment_attempt_entity'::regclass
                   AND conname = 'uq_payment_attempt_charge') THEN
        ALTER TABLE payment_attempt_entity
            ADD CONSTRAINT uq_payment_attempt_charge UNIQUE (stripe_charge_id);
    END IF;

    SELECT attnum INTO v_attnum FROM pg_attribute
    WHERE attrelid = 'payment_attempt_entity'::regclass AND attname = 'checkout_id';
    FOR r IN SELECT conname FROM pg_constraint WHERE conrelid = 'payment_attempt_entity'::regclass
             AND contype = 'f' AND conname <> 'fk_payment_attempt_checkout'
             AND confrelid = 'checkout_entity'::regclass AND conkey = ARRAY[v_attnum]
    LOOP
        EXECUTE format('ALTER TABLE payment_attempt_entity DROP CONSTRAINT %I', r.conname);
    END LOOP;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'payment_attempt_entity'::regclass
                   AND conname = 'fk_payment_attempt_checkout') THEN
        IF EXISTS (SELECT 1 FROM payment_attempt_entity a LEFT JOIN checkout_entity c
                   ON c.checkout_id = a.checkout_id WHERE c.checkout_id IS NULL) THEN
            RAISE EXCEPTION 'V5 refuses to add fk_payment_attempt_checkout: % orphan payment_attempt row(s) reference a missing checkout_id. Correct the data and re-run the migration.',
                (SELECT count(*) FROM payment_attempt_entity a LEFT JOIN checkout_entity c
                 ON c.checkout_id = a.checkout_id WHERE c.checkout_id IS NULL);
        END IF;
        ALTER TABLE payment_attempt_entity ADD CONSTRAINT fk_payment_attempt_checkout
            FOREIGN KEY (checkout_id) REFERENCES checkout_entity (checkout_id);
    END IF;

    ALTER TABLE payment_attempt_entity ALTER COLUMN version SET DEFAULT 0;
END $$;

CREATE INDEX IF NOT EXISTS idx_payment_attempt_status_updated
    ON payment_attempt_entity (status, updated_at);

-- ---- stripe_event_entity inbox completion (V4 parity, idempotent) ----
ALTER TABLE stripe_event_entity ADD COLUMN IF NOT EXISTS status VARCHAR(16);
ALTER TABLE stripe_event_entity ADD COLUMN IF NOT EXISTS payload TEXT;
ALTER TABLE stripe_event_entity ADD COLUMN IF NOT EXISTS payload_hash VARCHAR(64);
ALTER TABLE stripe_event_entity ADD COLUMN IF NOT EXISTS attempt_count INTEGER;
ALTER TABLE stripe_event_entity ADD COLUMN IF NOT EXISTS last_error VARCHAR(64);
ALTER TABLE stripe_event_entity ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(64);
ALTER TABLE stripe_event_entity ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE stripe_event_entity ADD COLUMN IF NOT EXISTS processing_started_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE stripe_event_entity ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP WITHOUT TIME ZONE;

-- Backfill rows written before the inbox lifecycle existed: a row with
-- processed_at set is complete, every other row is still work to do and
-- becomes immediately due. Never touches rows that already carry a status.
UPDATE stripe_event_entity
SET status = CASE WHEN processed_at IS NOT NULL THEN 'PROCESSED' ELSE 'RECEIVED' END
WHERE status IS NULL;

UPDATE stripe_event_entity
SET next_attempt_at = received_at
WHERE status = 'RECEIVED' AND next_attempt_at IS NULL;

UPDATE stripe_event_entity
SET payload_hash = ''
WHERE payload_hash IS NULL;

UPDATE stripe_event_entity
SET attempt_count = 0
WHERE attempt_count IS NULL;

DO $$
DECLARE
    v_violations BIGINT;
BEGIN
    SELECT count(*) INTO v_violations FROM stripe_event_entity WHERE status NOT IN
        ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED', 'MANUAL_REVIEW');
    IF v_violations > 0 THEN
        RAISE EXCEPTION 'V5 refuses to add chk_stripe_event_status: % row(s) in stripe_event_entity carry an illegal status. Correct the data and re-run the migration; webhook states are never rewritten automatically.',
            v_violations;
    END IF;

    ALTER TABLE stripe_event_entity ALTER COLUMN status SET NOT NULL;
    ALTER TABLE stripe_event_entity ALTER COLUMN status SET DEFAULT 'RECEIVED';
    ALTER TABLE stripe_event_entity ALTER COLUMN payload_hash SET NOT NULL;
    ALTER TABLE stripe_event_entity ALTER COLUMN payload_hash SET DEFAULT '';
    ALTER TABLE stripe_event_entity ALTER COLUMN attempt_count SET NOT NULL;
    ALTER TABLE stripe_event_entity ALTER COLUMN attempt_count SET DEFAULT 0;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'stripe_event_entity'::regclass
                   AND conname = 'chk_stripe_event_status') THEN
        ALTER TABLE stripe_event_entity ADD CONSTRAINT chk_stripe_event_status CHECK (status IN
            ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED', 'MANUAL_REVIEW'));
    END IF;

    ALTER TABLE stripe_event_entity DROP CONSTRAINT IF EXISTS stripe_event_entity_status_check;
END $$;

CREATE INDEX IF NOT EXISTS idx_stripe_event_recovery ON stripe_event_entity (status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_stripe_event_lease ON stripe_event_entity (status, lease_expires_at);

-- ======================================================================
-- D. Self-verification: every canonical artifact must exist now.
-- ======================================================================
DO $$
DECLARE
    v_missing TEXT := '';
    r RECORD;
BEGIN
    FOR r IN SELECT table_name, constraint_name FROM (VALUES
        ('checkout_entity', 'chk_checkout_total_positive'),
        ('checkout_entity', 'chk_checkout_amount_minor_positive'),
        ('checkout_entity', 'chk_checkout_currency'),
        ('checkout_entity', 'chk_checkout_status'),
        ('checkout_entity', 'uq_checkout_order_group'),
        ('checkout_entity', 'fk_checkout_user'),
        ('checkout_item_entity', 'chk_checkout_item_quantity_positive'),
        ('checkout_item_entity', 'chk_checkout_item_unit_price_non_negative'),
        ('checkout_item_entity', 'chk_checkout_item_line_total_non_negative'),
        ('checkout_item_entity', 'uq_checkout_item_product'),
        ('checkout_item_entity', 'fk_checkout_item_checkout'),
        ('checkout_item_entity', 'fk_checkout_item_product'),
        ('payment_attempt_entity', 'chk_payment_attempt_amount_positive'),
        ('payment_attempt_entity', 'chk_payment_attempt_currency'),
        ('payment_attempt_entity', 'chk_payment_attempt_status'),
        ('payment_attempt_entity', 'uq_payment_attempt_checkout'),
        ('payment_attempt_entity', 'uq_payment_attempt_idempotency'),
        ('payment_attempt_entity', 'uq_payment_attempt_charge'),
        ('payment_attempt_entity', 'fk_payment_attempt_checkout'),
        ('stripe_event_entity', 'chk_stripe_event_status')
    ) AS required(table_name, constraint_name)
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = r.table_name::regclass
                       AND conname = r.constraint_name) THEN
            v_missing := v_missing || r.table_name || '.' || r.constraint_name || ' ';
        END IF;
    END LOOP;

    FOR r IN SELECT indexname FROM (VALUES
        ('uq_checkout_live_per_user'),
        ('idx_checkout_user_status'),
        ('idx_checkout_expires_at'),
        ('idx_checkout_item_checkout'),
        ('idx_payment_attempt_status_updated'),
        ('idx_stripe_event_recovery'),
        ('idx_stripe_event_lease')
    ) AS required(indexname)
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = r.indexname) THEN
            v_missing := v_missing || 'index:' || r.indexname || ' ';
        END IF;
    END LOOP;

    IF v_missing <> '' THEN
        RAISE EXCEPTION 'V5 self-verification failed, missing canonical artifacts: %. '
            'The reconciliation is incomplete; investigate before retrying.', v_missing;
    END IF;
END $$;
