-- V9: one user per mailbox and one pending token per (mailbox, purpose).
--
-- Problem
-- -------
-- Two flows can create durable duplicates that nothing at the database level
-- prevents:
--   1. `signup()` checks `existsByUserMail` in one request, and the account is
--      inserted later, in a different request (`confirm()`). Two concurrent
--      confirmations of the same token, or a signup racing a reset, can create
--      two `user_entity` rows for one mailbox. `findByUserMail` then throws
--      `IncorrectResultSizeDataAccessException`, which permanently breaks login
--      for that address.
--   2. `PendingSignupStorage.store()` deletes the previous token for a mailbox
--      and purpose before inserting, but two concurrent `signup()` calls both
--      observe no previous token and both insert, leaving two live tokens for
--      the same purpose.
--
-- Fix
-- ---
-- 1. Re-point every child row that references a duplicate user (orders, order
--    groups, checkouts) to the surviving row (lowest `user_entity_id` per
--    mailbox) before the duplicates are deleted, then enforce one user per
--    mailbox with `uq_user_entity_user_mail`.
-- 2. Collapse duplicate pending tokens to one row per
--    (`user_mail`, `token_purpose`) and enforce it with
--    `uq_pending_one_per_purpose`, so a concurrent `store()` fails loudly with
--    a unique violation instead of silently leaving two live tokens.
--
-- The table guard mirrors V6. Flyway runs before Hibernate, but V5 already
-- builds these base tables on a fresh database, so the guard only protects the
-- (unreachable today) case where V5 was not applied.
--
-- `token_purpose` is never given a DEFAULT here (see V6): a write that omits
-- the purpose must fail, never silently acquire one.
--
-- This migration must never edit the checksum of V1-V8.

-- ======================================================================
-- 1. user_entity: merge duplicate mailboxes, keep the lowest id, enforce
--    uniqueness.
-- ======================================================================
DO $$
DECLARE
    v_live_checkout_conflicts BIGINT;
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = current_schema()
                 AND table_name = 'user_entity') THEN

        -- Re-point order lines owned by a duplicate user to the survivor.
        UPDATE order_entity o
        SET user_entity_id = keep.survivor_id
        FROM user_entity dup
        JOIN (SELECT user_mail, MIN(user_entity_id) AS survivor_id
              FROM user_entity
              WHERE user_mail IS NOT NULL
              GROUP BY user_mail) keep
          ON keep.user_mail = dup.user_mail
        WHERE o.user_entity_id = dup.user_entity_id
          AND dup.user_entity_id <> keep.survivor_id;

        -- Re-point order groups owned by a duplicate user to the survivor.
        UPDATE order_group_entity g
        SET user_entity_id = keep.survivor_id
        FROM user_entity dup
        JOIN (SELECT user_mail, MIN(user_entity_id) AS survivor_id
              FROM user_entity
              WHERE user_mail IS NOT NULL
              GROUP BY user_mail) keep
          ON keep.user_mail = dup.user_mail
        WHERE g.user_entity_id = dup.user_entity_id
          AND dup.user_entity_id <> keep.survivor_id;

        -- A live checkout is unique per user (uq_checkout_live_per_user). If
        -- both duplicates carry one, re-pointing would merge two live
        -- checkouts onto one user and the index would reject the update.
        -- Financial state is never rewritten automatically: stop with an
        -- actionable message instead.
        SELECT count(*) INTO v_live_checkout_conflicts
        FROM checkout_entity dup_checkout
        JOIN user_entity dup_user
          ON dup_user.user_entity_id = dup_checkout.user_entity_id
        JOIN (SELECT user_mail, MIN(user_entity_id) AS survivor_id
              FROM user_entity
              WHERE user_mail IS NOT NULL
              GROUP BY user_mail) keep
          ON keep.user_mail = dup_user.user_mail
        JOIN checkout_entity survivor_checkout
          ON survivor_checkout.user_entity_id = keep.survivor_id
         AND survivor_checkout.status IN ('PREPARED', 'PAYMENT_PROCESSING')
        WHERE dup_checkout.status IN ('PREPARED', 'PAYMENT_PROCESSING')
          AND dup_checkout.user_entity_id <> keep.survivor_id;

        IF v_live_checkout_conflicts > 0 THEN
            RAISE EXCEPTION 'V9 refuses to merge duplicate users: % live checkout row(s) would collide on uq_checkout_live_per_user. Resolve the duplicate accounts manually and re-run the migration.',
                v_live_checkout_conflicts;
        END IF;

        -- Re-point checkouts owned by a duplicate user to the survivor.
        UPDATE checkout_entity c
        SET user_entity_id = keep.survivor_id
        FROM user_entity dup
        JOIN (SELECT user_mail, MIN(user_entity_id) AS survivor_id
              FROM user_entity
              WHERE user_mail IS NOT NULL
              GROUP BY user_mail) keep
          ON keep.user_mail = dup.user_mail
        WHERE c.user_entity_id = dup.user_entity_id
          AND dup.user_entity_id <> keep.survivor_id;

        -- Every reference now points at the survivor, so the duplicates can go.
        DELETE FROM user_entity a
        USING user_entity b
        WHERE a.user_mail = b.user_mail
          AND a.user_entity_id > b.user_entity_id;

        IF NOT EXISTS (SELECT 1 FROM pg_constraint
                       WHERE conrelid = 'user_entity'::regclass
                         AND conname = 'uq_user_entity_user_mail') THEN
            ALTER TABLE user_entity
                ADD CONSTRAINT uq_user_entity_user_mail UNIQUE (user_mail);
        END IF;
    END IF;
END $$;

-- ======================================================================
-- 2. pending_signup_entity: one live token per (mailbox, purpose).
-- ======================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = current_schema()
                 AND table_name = 'pending_signup_entity') THEN

        -- Keep the most recently issued row (latest expiry, token as the
        -- deterministic tie-breaker); a token is a short-lived single-use
        -- artifact, so dropping a superseded sibling is safe.
        DELETE FROM pending_signup_entity a
        USING pending_signup_entity b
        WHERE a.user_mail = b.user_mail
          AND a.token_purpose = b.token_purpose
          AND (a.expires_at < b.expires_at
               OR (a.expires_at = b.expires_at AND a.token < b.token));

        IF NOT EXISTS (SELECT 1 FROM pg_constraint
                       WHERE conrelid = 'pending_signup_entity'::regclass
                         AND conname = 'uq_pending_one_per_purpose') THEN
            ALTER TABLE pending_signup_entity
                ADD CONSTRAINT uq_pending_one_per_purpose
                UNIQUE (user_mail, token_purpose);
        END IF;
    END IF;
END $$;
