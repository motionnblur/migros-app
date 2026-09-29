-- V11: optimistic edit version for product rows.
--
-- An administrator edit form sends the version it observed when it loaded the
-- product. Without that version the update is a blind read-modify-write of an
-- absolute stock count: a form opened before a checkout reservation or a restock
-- can write its stale count back and hand out stock that is already sold.
--
-- `BIGINT NOT NULL DEFAULT 0` matches the existing checkout_entity.version /
-- payment_attempt_entity.version precedent. V5 already creates product_entity
-- on every path (fresh or upgraded), so the table always exists when this runs
-- and `ADD COLUMN IF NOT EXISTS` is safe on both. Existing rows backfill to 0,
-- which is exactly the version an editor could have observed before this column
-- existed, so the first edit after the upgrade still succeeds.
--
-- Every writer of a product row must advance this column in the same statement.
-- JPA-managed writers (updateProduct, the locked checkout decrement) do it via
-- the entity's @Version; the bulk JPQL increment advances it explicitly because
-- a bulk statement bypasses entity version handling.
ALTER TABLE product_entity
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
