-- V3: durable payment-attempt state machine and Stripe webhook event
-- deduplication for the idempotent charge path.
--
-- Mirrors V2: the tables are created only when the base application schema
-- already exists. On a brand-new empty database Flyway runs before Hibernate,
-- so Hibernate creates the same tables from the explicit JPA mappings (column
-- names, non-null, precision, uniqueness and index annotations). On every
-- existing database this migration defines the production constraints, indexes
-- and foreign keys instead of relying on `ddl-auto=update`.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = current_schema()
                 AND table_name = 'checkout_entity') THEN

        CREATE TABLE IF NOT EXISTS payment_attempt_entity (
            attempt_id UUID PRIMARY KEY,
            checkout_id UUID NOT NULL REFERENCES checkout_entity (checkout_id),
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
            CONSTRAINT uq_payment_attempt_charge UNIQUE (stripe_charge_id)
        );

        CREATE INDEX IF NOT EXISTS idx_payment_attempt_status_updated
            ON payment_attempt_entity (status, updated_at);

        CREATE TABLE IF NOT EXISTS stripe_event_entity (
            event_id VARCHAR(255) PRIMARY KEY,
            event_type VARCHAR(100) NOT NULL,
            received_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
            processed_at TIMESTAMP WITHOUT TIME ZONE
        );
    END IF;
END $$;
