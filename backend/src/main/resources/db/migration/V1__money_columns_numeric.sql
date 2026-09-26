-- Convert monetary columns from binary floating point (REAL) to fixed NUMERIC(19, 2).
--
-- Stored prices are major-unit TRY amounts. Legacy columns were REAL, so values
-- such as 50.25 could be represented as 50.249999... This migration rounds every
-- legacy value to two decimal places before narrowing the type, preserving the
-- intended customer-facing amount.
--
-- The conversion is guarded per column so the same migration is safe both for an
-- existing populated schema and for a brand-new empty database. Flyway runs
-- before Hibernate creates the schema, so on an empty database the guarded blocks
-- are skipped and Hibernate then creates the columns directly as NUMERIC(19, 2).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema()
                 AND table_name = 'product_entity'
                 AND column_name = 'product_price') THEN
        ALTER TABLE product_entity
            ALTER COLUMN product_price TYPE NUMERIC(19, 2)
            USING round(product_price::numeric, 2);
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema()
                 AND table_name = 'product_entity'
                 AND column_name = 'product_discount') THEN
        ALTER TABLE product_entity
            ALTER COLUMN product_discount TYPE NUMERIC(19, 2)
            USING round(product_discount::numeric, 2);
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema()
                 AND table_name = 'order_entity'
                 AND column_name = 'price') THEN
        ALTER TABLE order_entity
            ALTER COLUMN price TYPE NUMERIC(19, 2)
            USING round(price::numeric, 2);
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema()
                 AND table_name = 'order_entity'
                 AND column_name = 'total_price') THEN
        ALTER TABLE order_entity
            ALTER COLUMN total_price TYPE NUMERIC(19, 2)
            USING round(total_price::numeric, 2);
    END IF;
END $$;
