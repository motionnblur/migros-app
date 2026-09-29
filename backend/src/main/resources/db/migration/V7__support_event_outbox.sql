-- V7: durable support-event outbox.
--
-- Problem: support chat mutations published to the external support service
-- inline, from inside the request. A slow or unavailable support service
-- therefore either blocked the customer's chat request or silently dropped the
-- event: the user-facing write was committed and the notification was lost with
-- no record that it was ever owed.
--
-- Fix: each chat mutation writes an outbox row in the SAME transaction as the
-- message insert/update/delete. A scheduled worker then claims due rows with a
-- bounded lease, delivers them outside any transaction, and retries failures
-- with backoff. A crash at any point leaves the row claimable rather than
-- lost, because "delivered" is a committed state, not a side effect.
--
-- Delivery is AT LEAST ONCE. The receiver deduplicates on `event_id`, which is
-- the primary key and is never regenerated across retries.
--
-- Per-customer ordering: `sequence_no` is a global monotonic counter, and a row
-- is only claimable when no earlier row for the same customer is still
-- undelivered. One customer therefore never has their events reordered by a
-- retry, while different customers remain fully parallel.
--
-- `payload` is the exact JSON body to POST. It may contain customer message
-- text, so it is operational data that is never logged and never returned by
-- the API. The internal key is never stored here.
--
-- Mirrors V4/V6: the create runs only when the base application schema already
-- exists. On a brand-new empty database Flyway runs before Hibernate, so
-- Hibernate creates the same table from the explicit mapping. This migration
-- must never edit the checksum of V1-V6.
CREATE TABLE IF NOT EXISTS support_outbox_entity (
    event_id VARCHAR(64) PRIMARY KEY,
    sequence_no BIGSERIAL NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    user_mail VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(64),
    lease_owner VARCHAR(64),
    lease_expires_at TIMESTAMP WITHOUT TIME ZONE,
    next_attempt_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    delivered_at TIMESTAMP WITHOUT TIME ZONE
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'chk_support_outbox_status'
                     AND conrelid = 'support_outbox_entity'::regclass) THEN
        ALTER TABLE support_outbox_entity
            ADD CONSTRAINT chk_support_outbox_status CHECK (status IN
                ('PENDING', 'PROCESSING', 'DELIVERED', 'FAILED'));
    END IF;

    -- Claim scan: "due rows, oldest first, that are at the head of their
    -- customer's queue".
    CREATE INDEX IF NOT EXISTS idx_support_outbox_due
        ON support_outbox_entity (status, next_attempt_at, sequence_no);

    -- Reclaim of rows whose worker died mid-delivery.
    CREATE INDEX IF NOT EXISTS idx_support_outbox_lease
        ON support_outbox_entity (status, lease_expires_at);

    -- Per-customer head-of-line check. Covers the NOT EXISTS "no earlier
    -- undelivered row for this customer" predicate.
    CREATE INDEX IF NOT EXISTS idx_support_outbox_customer
        ON support_outbox_entity (user_mail, sequence_no);

    -- Retention scan for delivered rows.
    CREATE INDEX IF NOT EXISTS idx_support_outbox_delivered
        ON support_outbox_entity (status, delivered_at);
END $$;
