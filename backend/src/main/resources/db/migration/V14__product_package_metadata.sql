-- V14: optional package size and unit for a product.
--
-- A grocery price is not comparable until you know what is being sold: a 45 TL
-- bottle and a 45 TL sack of the same product are not the same offer, and a
-- customer comparing two shelves cannot compare anything at all without the
-- package size. Until now that fact lived only in free text - a "1L" or "500g"
-- written into the product name - which is why it could not be displayed,
-- filtered, or priced per
-- unit: there was no number to divide by.
--
-- Two nullable columns:
--
--   package_amount NUMERIC(12,3)  how much is in one package
--   package_unit   VARCHAR(8)     which measure it is counted in
--
-- Both nullable, with NO backfill and NO default, on purpose:
--
--   * Not inferred. A quantity can be guessed from a product name or a
--     description, and a guessed quantity is worse than none: it produces a unit
--     price that looks authoritative and is not, which is the exact failure this
--     feature exists to remove. Every row that predates V14 keeps both columns
--     NULL, and the customer-facing listing shows no package line and no unit
--     price for it - the overwhelming majority of the catalogue.
--   * No default, so an insert that does not name them means "no package data",
--     not "1 G". A default would also make the two columns disagree with each
--     other on a row whose intent was simply to omit them.
--
-- NUMERIC(12,3) rather than a money type: this is a physical quantity, not
-- money, and it may need three decimals (a 0.033 L dose, a 2.5 G spice). Nine
-- integer digits is far beyond any real package and is derived from the column
-- itself so the application can reject an out-of-range value as a 400 instead of
-- letting the driver round or overflow mid-insert.
--
-- VARCHAR(8) is the widest accepted token ('ADET') with room to spare, and the
-- accepted set is closed: G, KG, ML, L, ADET. A unit-price basis derived from a
-- unit nobody defined is a number nobody can check, so the vocabulary is a fixed
-- list rather than free text.
--
-- The three CHECK constraints state invariants the application already enforces
-- in ProductCreationPolicy; they are here so a row cannot exist in a state the
-- rest of the system has no reading for. They are safe on every supported
-- starting point, including a database that already holds products, because
-- ADD COLUMN without a default makes all pre-existing rows NULL in both columns
-- and NULL/ NULL satisfies the pairing constraint. Unlike V13 there is no
-- legacy row shape that could violate them: the columns did not exist before
-- this migration, so no old data can hold a value in either of them.
--
--   chk_product_package_paired     both columns present, or neither. Half a
--                                   package is not "a package of unknown size" -
--                                   it is a row the unit-price arithmetic would
--                                   divide by a missing amount.
--   chk_product_package_positive   a present amount is greater than zero. Zero is
--                                   not "free", it is a division by zero, and a
--                                   negative one prices a product backwards.
--   chk_product_package_whole_adet ADET counts discrete items, so 1.5 of them
--                                   cannot be bought. G/KG/ML/L are continuous
--                                   and stay unrounded.
--
-- The unit whitelist is deliberately NOT a CHECK: it belongs to
-- ProductCreationPolicy, which reports an unsupported unit as a 400 the
-- administrator can act on, and the accepted set is expected to grow. Adding a
-- sixth unit would then also be a migration, which is exactly the coupling V14
-- avoids for everything else. An unrecognized unit that somehow reached the
-- database yields no unit price rather than a guessed one - see
-- ProductUnitPricePolicy.
--
-- No index: nothing filters, sorts or joins on package metadata. Adding one
-- would put a write cost on every product insert and edit for a predicate that
-- does not exist.
--
-- This migration must never change the checksum of V1-V13.

ALTER TABLE product_entity
    ADD COLUMN IF NOT EXISTS package_amount NUMERIC(12, 3);

ALTER TABLE product_entity
    ADD COLUMN IF NOT EXISTS package_unit VARCHAR(8);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_product_package_paired'
          AND conrelid = 'product_entity'::regclass
    ) THEN
        ALTER TABLE product_entity
            ADD CONSTRAINT chk_product_package_paired
                CHECK ((package_amount IS NULL) = (package_unit IS NULL));
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_product_package_positive'
          AND conrelid = 'product_entity'::regclass
    ) THEN
        ALTER TABLE product_entity
            ADD CONSTRAINT chk_product_package_positive
                CHECK (package_amount IS NULL OR package_amount > 0);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_product_package_whole_adet'
          AND conrelid = 'product_entity'::regclass
    ) THEN
        ALTER TABLE product_entity
            ADD CONSTRAINT chk_product_package_whole_adet
                CHECK (package_unit IS NULL
                       OR package_unit <> 'ADET'
                       OR package_amount = trunc(package_amount));
    END IF;
END $$;
