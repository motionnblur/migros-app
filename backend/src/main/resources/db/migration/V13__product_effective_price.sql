-- V13: materialized effective (discounted) price for product search.
--
-- Filtering and sorting a catalogue by "the price the product card shows" cannot be
-- done on product_price and product_discount without re-deriving the discounted
-- value for every row at query time, and re-deriving it in SQL is a second,
-- informal copy of ProductPricingPolicy's rounding sequence. Two copies of a
-- rounding sequence disagree at exactly the boundaries, and the symptom is a
-- customer who filters by a price band and cannot find a product whose card says
-- it is inside it. This column makes the sort key and the filter column literally
-- the stored number the listing renders, so they cannot drift.
--
-- Three steps, in this order, and the middle one is why they are three:
--
--   1. ADD COLUMN, nullable and with no default. Nullable because the next step is
--      what fills it; no default because a column default would have to encode the
--      discount arithmetic, which is the second copy this migration exists to
--      remove.
--   2. Backfill every existing row.
--   3. SET NOT NULL, after the backfill, so no row can be left null.
--
-- The backfill reproduces ProductPricingPolicy.effectivePrice exactly:
--
--     n = round(price, 2)
--     d <= 0            -> n
--     d >  0            -> round(n * (1 - round(d / 100, 6)), 2)
--
-- PostgreSQL's round(numeric, int) rounds halves away from zero, which is
-- BigDecimal's HALF_UP, and the discount factor is rounded to six decimals
-- *before* the multiply for the same reason the Java code does it that way:
-- rounding the factor first is what keeps the displayed price equal to the charged
-- price. The ordering of these operations is load-bearing; do not "simplify" it
-- into a single round(price * (1 - discount/100), 2).
--
-- COALESCE reproduces legacyListingPrice's tolerance for a row that is not a valid
-- product. A database older than V1 can still hold a null price or discount, and
-- the catalogue has always rendered those as zero rather than failing; the same
-- tolerance here is what keeps this column agreeing with the listing instead of
-- being the thing that breaks the upgrade.
--
-- No CHECK constraint is added on purpose. product_discount is only bounded by
-- application validation, so a pre-existing row with a discount above 100
-- produces a negative effective price here - and ProductPricingPolicy returns that
-- same negative number, which is what the card shows and what checkout already
-- refuses. A constraint would fail the upgrade on data the system currently
-- tolerates, replacing a rendering quirk with a database that will not start.
--
-- The backfill and the NOT NULL are unconditional because the money columns are
-- guaranteed to exist by the time this runs: V5 reconciles an already-present
-- legacy product_entity with ADD COLUMN IF NOT EXISTS for every column it declares,
-- so even the oldest supported schema has product_price and product_discount after
-- V5. (Those added columns are nullable, which is what the COALESCE above is for.)
--
-- The index serves the two new price predicates and the two price orderings. It is
-- a plain CREATE INDEX rather than CREATE INDEX CONCURRENTLY because Flyway runs
-- each migration in one transaction and CONCURRENTLY cannot run inside one.

ALTER TABLE product_entity
    ADD COLUMN IF NOT EXISTS effective_price NUMERIC(19, 2);

UPDATE product_entity
SET effective_price = CASE
    WHEN COALESCE(product_discount, 0) <= 0
        THEN round(COALESCE(product_price, 0), 2)
    ELSE round(
            round(COALESCE(product_price, 0), 2)
            * (1 - round(COALESCE(product_discount, 0) / 100, 6)),
            2)
END;

ALTER TABLE product_entity
    ALTER COLUMN effective_price SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_product_effective_price
    ON product_entity (effective_price);
