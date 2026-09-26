-- V4: durable Stripe webhook inbox.
--
-- Problem: V3 committed the event id before processing and treated every later
-- delivery of a known id as a duplicate. A process crash between receipt and
-- processing therefore left the event permanently unprocessed: Stripe retries
-- were discarded because the row merely existed.
--
-- Fix: the inbox row now carries its own lifecycle. An event is complete only
-- when status = 'PROCESSED'. Crash windows leave the row RECEIVED, PROCESSING
-- with an expired lease, or FAILED with a due next_attempt_at, all of which
-- are reclaimable by redelivery or by the scheduled recovery job. The verified
-- raw payload is stored so an unprocessed event can be replayed without
-- waiting for another Stripe delivery.
--
-- Sensitivity: payload is operational data that may contain cardholder-adjacent
-- identifiers. It is never logged, never returned by the API, and is deleted
-- by retention cleanup only after a documented retention period and only for
-- PROCESSED events. The webhook secret and signatures are never stored here.
--
-- Mirrors V2/V3: the alter runs only when the base application schema already
-- exists. On a brand-new empty database Flyway runs before Hibernate, so
-- Hibernate creates the same columns from the explicit JPA mappings on
-- StripeEventEntity. This migration must never edit the checksum of V3.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = current_schema()
                 AND table_name = 'stripe_event_entity') THEN

        ALTER TABLE stripe_event_entity ADD COLUMN IF NOT EXISTS status VARCHAR(16);
        ALTER TABLE stripe_event_entity ADD COLUMN IF NOT EXISTS payload TEXT;
        ALTER TABLE stripe_event_entity ADD COLUMN IF NOT EXISTS payload_hash VARCHAR(64)
            NOT NULL DEFAULT '';
        ALTER TABLE stripe_event_entity ADD COLUMN IF NOT EXISTS attempt_count INTEGER
            NOT NULL DEFAULT 0;
        ALTER TABLE stripe_event_entity ADD COLUMN IF NOT EXISTS last_error VARCHAR(64);
        ALTER TABLE stripe_event_entity ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(64);
        ALTER TABLE stripe_event_entity ADD COLUMN IF NOT EXISTS lease_expires_at
            TIMESTAMP WITHOUT TIME ZONE;
        ALTER TABLE stripe_event_entity ADD COLUMN IF NOT EXISTS processing_started_at
            TIMESTAMP WITHOUT TIME ZONE;
        ALTER TABLE stripe_event_entity ADD COLUMN IF NOT EXISTS next_attempt_at
            TIMESTAMP WITHOUT TIME ZONE;

        -- Backfill rows written by V3: a row with processed_at set is complete,
        -- every other row is still work to do and becomes immediately due.
        UPDATE stripe_event_entity
        SET status = CASE WHEN processed_at IS NOT NULL THEN 'PROCESSED' ELSE 'RECEIVED' END
        WHERE status IS NULL;

        UPDATE stripe_event_entity
        SET next_attempt_at = received_at
        WHERE status = 'RECEIVED' AND next_attempt_at IS NULL;

        ALTER TABLE stripe_event_entity ALTER COLUMN status SET NOT NULL;
        ALTER TABLE stripe_event_entity ALTER COLUMN status SET DEFAULT 'RECEIVED';

        ALTER TABLE stripe_event_entity DROP CONSTRAINT IF EXISTS chk_stripe_event_status;
        ALTER TABLE stripe_event_entity ADD CONSTRAINT chk_stripe_event_status CHECK (status IN
            ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED', 'MANUAL_REVIEW'));

        CREATE INDEX IF NOT EXISTS idx_stripe_event_recovery
            ON stripe_event_entity (status, next_attempt_at);
        CREATE INDEX IF NOT EXISTS idx_stripe_event_lease
            ON stripe_event_entity (status, lease_expires_at);
    END IF;
END $$;
