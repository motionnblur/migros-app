-- V12: durable cleanup queue for obsolete product image files.
--
-- Problem: replacing a product image or deleting a product leaves the old file
-- on disk with nothing referencing it. Doing that from an in-memory
-- after-commit callback is not enough - a crash between the commit and the
-- callback loses the only remaining record that the file is obsolete, and the
-- bytes stay forever. Doing it inline, inside the request transaction, is
-- worse: a slow or failing filesystem would either block the administrator's
-- request or roll back a product edit that had already been decided.
--
-- Fix: the transaction that stops referencing a file writes one row here
-- recording the file's canonical identity. A scheduled worker then claims due
-- rows with a bounded lease, checks that nothing still references the file,
-- deletes it outside any database transaction, and records the outcome.
--
-- This is deliberately NOT the support outbox and shares nothing with it: no
-- payloads, no HTTP delivery, no ordering, no event ids. A support event is
-- owed to a remote receiver that deduplicates; a file deletion is a local,
-- idempotent filesystem effect that is safe to repeat, so what it needs is a
-- lease, a retry and a durable identity - nothing else.
--
-- `file_identity` is the canonical bare file name, never a stored path. Stored
-- values can be legacy absolute paths or use either separator, so the queue
-- stores what the file IS rather than how it was spelled; the worker
-- re-canonicalizes every current reference with the same rules before deleting.
--
-- `cleanup_id` is a stable primary key generated once at enqueue and never
-- regenerated, so a retry always refers to the same piece of work.
--
-- There is deliberately NO terminal failure state. A file that cannot be
-- deleted is still a file nobody references; parking the row terminally would
-- abandon the work silently, exactly the mistake V8 had to undo for the support
-- outbox. `product-image.cleanup.alert-after-attempts` only raises the log to
-- ERROR.
--
-- Outstanding work is deduplicated by canonical file identity: two products
-- that shared one legacy file, or the same file enqueued by both a replacement
-- and a deletion, produce one row while either is still owed. A COMPLETED row
-- does not block a later obligation for the same name, because a file that has
-- actually been removed can no longer be the target of a reference.
--
-- Mirrors V7/V11: the create runs on every path. Flyway runs before Hibernate,
-- so this is the only thing that creates the table and Hibernate's
-- ddl-auto=validate must find it on both fresh and upgraded databases. This
-- migration must never edit the checksum of V1-V11.
CREATE TABLE IF NOT EXISTS product_image_cleanup_entity (
    cleanup_id VARCHAR(64) PRIMARY KEY,
    sequence_no BIGSERIAL NOT NULL,
    file_identity VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(64),
    lease_owner VARCHAR(64),
    lease_expires_at TIMESTAMP WITHOUT TIME ZONE,
    next_attempt_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITHOUT TIME ZONE
);

DO $$
BEGIN
    -- PENDING, PROCESSING, COMPLETED. No FAILED: pending work is preserved
    -- indefinitely, so a permanently undeletable file stays visible as work
    -- instead of disappearing from the queue.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'chk_product_image_cleanup_status'
                     AND conrelid = 'product_image_cleanup_entity'::regclass) THEN
        ALTER TABLE product_image_cleanup_entity
            ADD CONSTRAINT chk_product_image_cleanup_status CHECK (status IN
                ('PENDING', 'PROCESSING', 'COMPLETED'));
    END IF;

    -- The identity is a bare file name confined to the upload directory. A
    -- value carrying a separator, a drive colon, or a relative-path token can
    -- never be resolved to a file inside the upload directory, so it is
    -- rejected at the boundary rather than at delete time. This is the schema
    -- refusing what FileService would refuse anyway: defence in depth for a
    -- value that is about to be handed to a filesystem delete.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'chk_product_image_cleanup_identity'
                     AND conrelid = 'product_image_cleanup_entity'::regclass) THEN
        ALTER TABLE product_image_cleanup_entity
            ADD CONSTRAINT chk_product_image_cleanup_identity CHECK (
                file_identity <> ''
                AND file_identity <> '.'
                AND file_identity <> '..'
                AND file_identity !~ '[:/\\]');
    END IF;

    -- Claim scan: due rows, oldest first.
    CREATE INDEX IF NOT EXISTS idx_product_image_cleanup_due
        ON product_image_cleanup_entity (status, next_attempt_at, sequence_no);

    -- Reclaim of rows whose worker died mid-deletion.
    CREATE INDEX IF NOT EXISTS idx_product_image_cleanup_lease
        ON product_image_cleanup_entity (status, lease_expires_at);

    -- One outstanding obligation per file, whatever number of products, image
    -- rows or admin operations asked for its removal.
    CREATE UNIQUE INDEX IF NOT EXISTS uq_product_image_cleanup_outstanding
        ON product_image_cleanup_entity (file_identity)
        WHERE status IN ('PENDING', 'PROCESSING');

    -- Retention scan for completed rows.
    CREATE INDEX IF NOT EXISTS idx_product_image_cleanup_completed
        ON product_image_cleanup_entity (status, completed_at);
END $$;
