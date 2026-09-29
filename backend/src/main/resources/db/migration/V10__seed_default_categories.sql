-- V10: seed the default product categories.
--
-- The 18 categories used to be inserted by a `CommandLineRunner`
-- (`StartupConfiguration`) on every boot, one `existsByCategoryName` query per
-- row. Moving the data into Flyway makes the seed part of the schema history:
-- it runs exactly once per database, participates in the same transactional
-- migrate step, and no longer executes a query on every application start.
--
-- Idempotency
-- -----------
-- The seed is written as a set insert guarded by `NOT EXISTS` on
-- `category_name`, reproducing the old seeder's `existsByCategoryName` check.
-- An existing populated database therefore keeps every row it already has (its
-- own `category_id` values included) and only gains the names that are missing;
-- a fresh database receives all 18 rows with `category_id` 1..18.
--
-- Data caveat (do not "fix" silently)
-- -----------------------------------
-- Row 12 is `'Kişisel Bakım,Kozmetik, Sağlık'`. The backend review flagged the
-- comma between `Bakım` and `Kozmetik` as a possible typo for a space. The
-- value is reproduced byte-for-byte from the legacy seeder and is intentionally
-- preserved pending a product-owner decision: the live UI subcategory
-- grouping and any externally stored references depend on the exact string, so
-- changing it here would be a silent data migration.
--
-- `category_entity_id` is the BIGSERIAL primary key and is deliberately left to
-- the sequence; `category_id` is the separate legacy integer column the seeder
-- populated.

INSERT INTO category_entity (category_id, category_name)
SELECT seed.category_id, seed.category_name
FROM (VALUES
    (1, 'Yılbaşı'),
    (2, 'Meyve, Sebze'),
    (3, 'Süt, Kahvaltılık'),
    (4, 'Temel Gıda'),
    (5, 'Meze, Hazır yemek, Donut'),
    (6, 'İçecek'),
    (7, 'Dondurma'),
    (8, 'Atistirmalik'),
    (9, 'Fırın, Pastane'),
    (10, 'Deterjan, Temizlik'),
    (11, 'Kağıt, Islak mendil'),
    (12, 'Kişisel Bakım,Kozmetik, Sağlık'),
    (13, 'Bebek'),
    (14, 'Ev, Yaşam'),
    (15, 'Kitap, Kırtasiye, Oyuncak'),
    (16, 'Çiçek'),
    (17, 'Pet Shop'),
    (18, 'Elektronik')
) AS seed(category_id, category_name)
WHERE NOT EXISTS (
    SELECT 1
    FROM category_entity existing
    WHERE existing.category_name = seed.category_name
);
