-- V2: immutable checkout snapshot, one-live-checkout-per-user enforcement, and
-- reserved-stock bookkeeping for the Stripe payment path.
--
-- The tables are created only when the base application schema already exists.
-- This mirrors V1: on a brand-new empty database Flyway runs before Hibernate,
-- so Hibernate creates the same tables from the JPA mappings (explicit column
-- names, non-null, precision and uniqueness annotations). On every existing
-- database this migration is what defines the production constraints, indexes
-- and foreign keys, rather than relying on `ddl-auto=update`.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = current_schema()
                 AND table_name = 'user_entity') THEN

        CREATE TABLE IF NOT EXISTS checkout_entity (
            checkout_id UUID PRIMARY KEY,
            user_entity_id BIGINT NOT NULL REFERENCES user_entity (user_entity_id),
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
            CONSTRAINT uq_checkout_order_group UNIQUE (order_group_entity_id)
        );

        CREATE INDEX IF NOT EXISTS idx_checkout_user_status
            ON checkout_entity (user_entity_id, status);
        CREATE INDEX IF NOT EXISTS idx_checkout_expires_at
            ON checkout_entity (expires_at);
        -- At most one live (unpaid) checkout per user. PostgreSQL partial unique
        -- index; multiple terminal rows are still allowed.
        CREATE UNIQUE INDEX IF NOT EXISTS uq_checkout_live_per_user
            ON checkout_entity (user_entity_id)
            WHERE status IN ('PREPARED', 'PAYMENT_PROCESSING');

        CREATE TABLE IF NOT EXISTS checkout_item_entity (
            checkout_item_entity_id BIGSERIAL PRIMARY KEY,
            checkout_id UUID NOT NULL
                REFERENCES checkout_entity (checkout_id) ON DELETE CASCADE,
            product_entity_id BIGINT NOT NULL
                REFERENCES product_entity (product_entity_id),
            product_name VARCHAR(255) NOT NULL,
            quantity INTEGER NOT NULL,
            unit_price NUMERIC(19, 2) NOT NULL,
            line_total NUMERIC(19, 2) NOT NULL,
            CONSTRAINT chk_checkout_item_quantity_positive CHECK (quantity > 0),
            CONSTRAINT chk_checkout_item_unit_price_non_negative CHECK (unit_price >= 0),
            CONSTRAINT chk_checkout_item_line_total_non_negative CHECK (line_total >= 0),
            CONSTRAINT uq_checkout_item_product UNIQUE (checkout_id, product_entity_id)
        );

        CREATE INDEX IF NOT EXISTS idx_checkout_item_checkout
            ON checkout_item_entity (checkout_id);
    END IF;
END $$;
