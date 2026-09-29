-- V6: pending-token purpose.
--
-- Problem: signup-confirmation tokens and password-reset tokens share the
-- `pending_signup_entity` table and are both keyed by an opaque random string.
-- Nothing recorded why a token was issued, so any token could be redeemed
-- against any pending-token endpoint: a signup token could reset a password and
-- a password-reset token could confirm an account. A password-reset link that
-- had already been used by the legitimate owner, but not yet redeemed, could
-- therefore be replayed by whoever obtained the mail.
--
-- Fix: every token now carries the single purpose it was issued for, and the
-- consuming endpoint only accepts a token whose purpose matches. Tokens are
-- therefore single-use for exactly one action.
--
-- Backfill: existing rows predate the column, so their purpose is unknowable.
-- Rather than guess (which would re-open the cross-endpoint replay) they are
-- deleted. This is safe because a pending token is a short-lived, single-use
-- artifact; an in-flight signup or reset simply fails with the existing
-- "token not found" response and the user repeats the request. Deleting is the
-- fail-closed direction: keeping a row with a guessed purpose would keep the
-- very replay this migration exists to close.
--
-- No column DEFAULT is installed. A default would let a purpose-less insert
-- succeed by silently acquiring a purpose, which is exactly the guess this
-- migration refuses to make. A write that omits the purpose must fail.
--
-- Mirrors V4: the alter runs only when the table already exists. On a
-- brand-new empty database Flyway runs before Hibernate, so Hibernate creates
-- the same NOT NULL column and the CHECK constraint from the explicit JPA
-- mapping. This migration must never edit the checksum of V1-V5.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = current_schema()
                 AND table_name = 'pending_signup_entity') THEN

        ALTER TABLE pending_signup_entity ADD COLUMN IF NOT EXISTS token_purpose VARCHAR(32);

        -- Unknowable purpose: drop rather than guess. See the header note.
        DELETE FROM pending_signup_entity WHERE token_purpose IS NULL;

        ALTER TABLE pending_signup_entity ALTER COLUMN token_purpose SET NOT NULL;

        ALTER TABLE pending_signup_entity DROP CONSTRAINT IF EXISTS chk_pending_token_purpose;
        ALTER TABLE pending_signup_entity ADD CONSTRAINT chk_pending_token_purpose
            CHECK (token_purpose IN ('SIGNUP', 'PASSWORD_RESET'));

        -- The hot lookup is "the one pending token for this mailbox, for this
        -- purpose". Serving it from the index keeps the per-user token
        -- replacement from scanning the whole table.
        CREATE INDEX IF NOT EXISTS idx_pending_signup_mail_purpose
            ON pending_signup_entity (user_mail, token_purpose);
    END IF;
END $$;
