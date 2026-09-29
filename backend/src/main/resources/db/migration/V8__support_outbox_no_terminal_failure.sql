-- V8: the support outbox no longer has a terminal failure state.
--
-- Problem: V7 introduced a FAILED status that a record was moved to once it
-- spent its attempt budget. Two things were wrong with it.
--
--   1. The event was lost. The support service never received it and never
--      would, because a FAILED row is excluded from the claim scan.
--   2. Worse, it reordered the conversation. Per-customer ordering holds back a
--      later event only while an earlier one for the same customer is still
--      PENDING or PROCESSING. A FAILED row satisfies neither, so it stopped
--      blocking the queue and every subsequent event for that customer was
--      delivered ahead of the one that never arrived.
--
-- Fix: FAILED is removed from the allowed set and any row already parked in it
-- goes back to PENDING with its backoff elapsed. Those events are still owed -
-- the receiver never acknowledged them - so re-arming them restores at-least-once
-- delivery and puts the customer's queue back in order. Delivery from here on
-- never terminates: a failed attempt is rescheduled with capped exponential
-- backoff indefinitely, and the configured attempt count is only a logging
-- threshold for operator attention.
--
-- Mirrors V7: the updates run only when the outbox table exists. On a brand-new
-- empty database Flyway runs before Hibernate, so the table is created with this
-- constraint already in force. This migration must never edit the checksum of
-- V1-V7.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = current_schema()
                 AND table_name = 'support_outbox_entity') THEN

        -- Still owed, so re-arm it: backoff already elapsed, owner-free.
        UPDATE support_outbox_entity
        SET status = 'PENDING',
            next_attempt_at = LEAST(next_attempt_at, now()),
            lease_owner = NULL,
            lease_expires_at = NULL
        WHERE status = 'FAILED';

        ALTER TABLE support_outbox_entity DROP CONSTRAINT IF EXISTS chk_support_outbox_status;
        ALTER TABLE support_outbox_entity ADD CONSTRAINT chk_support_outbox_status
            CHECK (status IN ('PENDING', 'PROCESSING', 'DELIVERED'));
    END IF;
END $$;
