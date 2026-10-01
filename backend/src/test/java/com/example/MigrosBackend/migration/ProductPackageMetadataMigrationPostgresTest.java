package com.example.MigrosBackend.migration;

import com.example.MigrosBackend.helper.ProductUnitPricePolicy;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V14 adds the two optional package columns, and it has to land on both shapes of
 * database: an empty one, and one that already holds products.
 *
 * <p>The populated case is the one that can fail quietly. This migration does no
 * backfill on purpose - a package size is not a fact about a row that can be
 * derived, and inferring one from a product name produces a unit price that looks
 * authoritative and is wrong. So the upgrade's real job is to change nothing about
 * the rows already there, and to make the new columns agree with the entity
 * mapping on both shapes.
 *
 * <p>The constraints are the other half of it. They state invariants the
 * application already enforces, which is only safe because the columns did not
 * exist before this migration: no pre-existing row can hold a value in either of
 * them, so there is no legacy shape for a constraint to fail on. That is asserted
 * rather than assumed, because an upgrade that cannot start is worse than a
 * missing feature.
 */
@Testcontainers
class ProductPackageMetadataMigrationPostgresTest {

    private static final String V13 = "13";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @BeforeEach
    void resetSchema() throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
    }

    // -------------------------------------------------------------------------
    // Fresh schema
    // -------------------------------------------------------------------------

    /**
     * The declared shape has to match the entity mapping exactly, because
     * Hibernate runs with {@code ddl-auto=validate} and refuses to start when the
     * two disagree. NUMERIC(12, 3) and not NUMERIC(19, 2): a package is measured,
     * not priced, and a dose in millilitres needs three decimals.
     */
    @Test
    void aFreshDatabaseGetsBothColumnsWithTheShapeTheEntityMappingValidates() throws SQLException {
        MigrateResult result = migrate();

        assertEquals(MigrationTestSupport.versionedMigrationCount(), result.migrationsExecuted,
                "a first migrate must apply every versioned script, including V14");
        assertEquals(1, singleLong("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version = '14' AND success"),
                "Flyway must record the package-metadata migration on an empty database");
        assertColumnShape();
    }

    /**
     * No default on either column.
     *
     * <p>A default would turn "the administrator did not enter a package size"
     * into "this product contains one unit", and would also make the two columns
     * disagree with each other on a row whose intent was to omit them.
     */
    @Test
    void neitherColumnHasADefault() throws SQLException {
        migrate();

        assertNull(columnDefaultOf("package_amount"));
        assertNull(columnDefaultOf("package_unit"));
    }

    @Test
    void bothColumnsAreNullableBecauseAbsenceIsTheOrdinaryState() throws SQLException {
        migrate();

        assertEquals("YES", isNullableOf("package_amount"));
        assertEquals("YES", isNullableOf("package_unit"));
    }

    // -------------------------------------------------------------------------
    // Populated schema
    // -------------------------------------------------------------------------

    /**
     * The upgrade must leave the rows it touches exactly as it found them. This is
     * the whole claim of the migration: a size that was never entered cannot be
     * recovered, so the upgrade's job is to change nothing.
     */
    @Test
    void anUpgradedDatabaseLeavesEveryExistingRowWithoutPackageMetadata() throws SQLException {
        migrateTo(V13);
        insertLegacyProduct("Legacy Milk 1L", "24.99", "10.00", 7);
        insertLegacyProduct("Legacy Eggs", "79.90", "0.00", 3);

        migrate();

        assertColumnShape();
        assertEquals(2, singleLong("SELECT COUNT(*) FROM product_entity"));
        assertEquals(0, singleLong("SELECT COUNT(*) FROM product_entity "
                        + "WHERE package_amount IS NOT NULL OR package_unit IS NOT NULL"),
                "a package size must never be inferred from a product name or a description, "
                        + "so an upgraded row reports no size and no unit price");
    }

    @Test
    void anUpgradedDatabaseKeepsTheColumnsThisMigrationDidNotMeanToTouch() throws SQLException {
        migrateTo(V13);
        insertLegacyProduct("Legacy Milk 1L", "24.99", "10.00", 7);

        migrate();

        assertEquals("Legacy Milk 1L", textOf("SELECT product_name FROM product_entity"));
        assertEquals("24.99", textOf("SELECT product_price FROM product_entity"));
        assertEquals("10.00", textOf("SELECT product_discount FROM product_entity"));
        assertEquals(7, singleLong("SELECT product_count FROM product_entity"));
        assertEquals("24.99", textOf("SELECT effective_price FROM product_entity"),
                "the materialized effective price is V13's business; V14 must leave the stored "
                        + "number exactly as the row already held it");
        assertEquals(0, singleLong("SELECT version FROM product_entity"),
                "V14 must not touch the edit version");
    }

    /**
     * The oldest supported schema: a {@code product_entity} that predates every
     * column the later migrations add. V5 reconciles it with nullable
     * {@code ADD COLUMN IF NOT EXISTS}, so by the time V14 runs the table is
     * missing the package columns entirely and has to gain them.
     */
    @Test
    void theOldestSupportedSchemaGainsBothColumnsAndNothingElse() throws SQLException {
        execute("CREATE TABLE product_entity ("
                + "product_entity_id BIGSERIAL PRIMARY KEY, "
                + "product_name VARCHAR(255) NOT NULL)");
        execute("INSERT INTO product_entity (product_name) VALUES ('Priceless')");

        migrate();

        assertColumnShape();
        assertEquals(0, singleLong("SELECT COUNT(*) FROM product_entity "
                + "WHERE package_amount IS NOT NULL OR package_unit IS NOT NULL"),
                "there is nothing on this row to derive a size from, and inventing one would be "
                        + "the exact failure this feature exists to prevent");
        assertEquals(1, singleLong("SELECT COUNT(*) FROM product_entity"));
    }

    /**
     * Safe to re-run: a rolled-back deploy that left V14 applied must not fail the
     * next startup. The {@code IF NOT EXISTS} guards and the constraint-existence
     * checks exist for this.
     */
    @Test
    void reRunningTheMigrationChangesNothing() throws SQLException {
        migrateTo(V13);
        insertLegacyProduct("Idempotent Butter", "55.00", "0.00", 2);

        migrate();
        migrate();

        assertColumnShape();
        assertEquals("55.00", textOf("SELECT product_price FROM product_entity"));
        assertEquals(3, singleLong("SELECT COUNT(*) FROM pg_constraint "
                + "WHERE conname LIKE 'chk_product_package%' AND conrelid = 'product_entity'::regclass"),
                "the guards must not add a second copy of a constraint");
    }

    // -------------------------------------------------------------------------
    // What the schema itself refuses
    // -------------------------------------------------------------------------

    /**
     * Half a package is not "a package of unknown size" - it is a row the unit
     * price arithmetic has no denominator for, and the application refuses to
     * create one. The constraint is what keeps that true for a write that did not
     * come through the application.
     */
    @Test
    void anAmountWithoutAUnitIsRefusedByTheSchema() throws SQLException {
        migrate();

        assertThrows(SQLException.class,
                () -> execute("INSERT INTO product_entity (product_name, subcategory_name, product_count, "
                        + "product_price, product_discount, effective_price, product_description, "
                        + "package_amount) VALUES ('Half', 'general', 1, 5.00, 0.00, 5.00, 'd', 500.000)"),
                "a row with a size and no measure has no unit price and must not exist");
    }

    @Test
    void aUnitWithoutAnAmountIsRefusedByTheSchema() throws SQLException {
        migrate();

        assertThrows(SQLException.class,
                () -> execute("INSERT INTO product_entity (product_name, subcategory_name, product_count, "
                        + "product_price, product_discount, effective_price, product_description, "
                        + "package_unit) VALUES ('Half', 'general', 1, 5.00, 0.00, 5.00, 'd', 'KG')"),
                "a unit with no amount has no divisor at all");
    }

    @Test
    void aZeroOrNegativeAmountIsRefusedByTheSchema() throws SQLException {
        migrate();

        assertThrows(SQLException.class,
                () -> execute("INSERT INTO product_entity (product_name, subcategory_name, product_count, "
                        + "product_price, product_discount, effective_price, product_description, "
                        + "package_amount, package_unit) VALUES ('Free', 'general', 1, 5.00, 0.00, 5.00, "
                        + "'d', 0.000, 'KG')"),
                "zero is a division by zero, not a free package");
        assertThrows(SQLException.class,
                () -> execute("INSERT INTO product_entity (product_name, subcategory_name, product_count, "
                        + "product_price, product_discount, effective_price, product_description, "
                        + "package_amount, package_unit) VALUES ('Backwards', 'general', 1, 5.00, 0.00, "
                        + "5.00, 'd', -1.000, 'KG')"),
                "a negative amount prices a package backwards");
    }

    /**
     * ADET counts discrete items. A gram or a litre is continuous and stays
     * fractional, which is what makes the rule about counting rather than about
     * decimals.
     */
    @Test
    void onlyTheItemUnitRefusesAFractionalAmount() throws SQLException {
        migrate();

        assertThrows(SQLException.class,
                () -> execute("INSERT INTO product_entity (product_name, subcategory_name, product_count, "
                        + "product_price, product_discount, effective_price, product_description, "
                        + "package_amount, package_unit) VALUES ('Half Eggs', 'general', 1, 5.00, 0.00, "
                        + "5.00, 'd', 1.500, 'ADET')"),
                "half an egg is not a thing that can be sold");

        execute("INSERT INTO product_entity (product_name, subcategory_name, product_count, "
                + "product_price, product_discount, effective_price, product_description, "
                + "package_amount, package_unit) VALUES ('Six Eggs', 'general', 1, 5.00, 0.00, 5.00, "
                + "'d', 6.000, 'ADET')");
        execute("INSERT INTO product_entity (product_name, subcategory_name, product_count, "
                + "product_price, product_discount, effective_price, product_description, "
                + "package_amount, package_unit) VALUES ('Half Litre', 'general', 1, 5.00, 0.00, 5.00, "
                + "'d', 0.500, 'L')");
        assertEquals(2, singleLong("SELECT COUNT(*) FROM product_entity "
                + "WHERE package_amount IS NOT NULL"));
    }

    /**
     * A complete pair is accepted, at the full precision the column allows. This is
     * the shape every future writer produces, so it is the one the upgrade has to
     * leave room for.
     */
    @Test
    void aCompletePairIsAcceptedAtFullPrecision() throws SQLException {
        migrate();

        execute("INSERT INTO product_entity (product_name, subcategory_name, product_count, "
                + "product_price, product_discount, effective_price, product_description, "
                + "package_amount, package_unit) VALUES ('Precise', 'general', 1, 5.00, 0.00, 5.00, "
                + "'d', 999999999.999, 'G')");

        assertEquals("999999999.999", textOf("SELECT package_amount FROM product_entity"));
        assertEquals("G", textOf("SELECT package_unit FROM product_entity"));
    }

    /**
     * The unit whitelist is deliberately not a constraint. It belongs to the
     * application, which reports an unsupported unit as a 400 the administrator can
     * act on, and the accepted set is expected to grow; a constraint would make
     * adding a sixth unit a migration as well.
     *
     * <p>What has to hold even so is that an unrecognized unit yields no unit price
     * rather than a guessed one - that is
     * {@code ProductUnitPricePolicy}'s rule, asserted here so the schema's freedom
     * and the arithmetic's refusal are stated together.
     */
    @Test
    void anUnrecognizedUnitIsStoredButCannotBePricedPer() throws SQLException {
        migrate();

        execute("INSERT INTO product_entity (product_name, subcategory_name, product_count, "
                + "product_price, product_discount, effective_price, product_description, "
                + "package_amount, package_unit) VALUES ('Odd Unit', 'general', 1, 5.00, 0.00, 5.00, "
                + "'d', 500.000, 'GRAM')");

        assertEquals("GRAM", textOf("SELECT package_unit FROM product_entity"));
        assertNull(ProductUnitPricePolicy.unitPrice(
                        new BigDecimal("5.00"), new BigDecimal("500"), "GRAM"),
                "a unit nobody defined has no basis to be divided by, so no unit price is produced");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertColumnShape() throws SQLException {
        assertColumn("package_amount", "numeric", 12, 3);
        assertColumn("package_unit", "character varying", null, null);
    }

    private void assertColumn(String column, String dataType, Integer precision, Integer scale)
            throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT data_type, numeric_precision, numeric_scale, character_maximum_length "
                             + "FROM information_schema.columns WHERE table_schema = 'public' "
                             + "AND table_name = 'product_entity' AND column_name = '" + column + "'")) {
            assertTrue(rs.next(), "product_entity." + column + " must exist after V14");
            assertEquals(dataType, rs.getString("data_type"));
            if (precision != null) {
                assertEquals(precision.intValue(), rs.getInt("numeric_precision"));
                assertEquals(scale.intValue(), rs.getInt("numeric_scale"));
            }
            if ("character varying".equals(dataType)) {
                // The widest accepted token is ADET; eight leaves room without
                // leaving room for a free-text unit nobody defined.
                assertTrue(rs.getInt("character_maximum_length") >= 4,
                        "package_unit must hold the widest accepted token");
            }
        }
    }

    private String columnDefaultOf(String column) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT column_default FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = 'product_entity' "
                             + "AND column_name = '" + column + "'")) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private String isNullableOf(String column) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT is_nullable FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = 'product_entity' "
                             + "AND column_name = '" + column + "'")) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    /**
     * A row as it looked once V13 had run.
     *
     * <p>{@code effective_price} is included because this migration's starting
     * point is a V13 schema, where that column is already {@code NOT NULL} - so
     * the row being upgraded has to look like a real post-V13 row, not like a
     * pre-V13 one.
     */
    private void insertLegacyProduct(String name, String price, String discount, int stock)
            throws SQLException {
        execute("INSERT INTO product_entity (product_name, subcategory_name, product_count, "
                + "product_price, product_discount, effective_price, product_description) VALUES ('"
                + name + "', 'general', " + stock + ", " + price + ", " + discount + ", " + price
                + ", 'migration guard')");
    }

    private String textOf(String sql) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next(), "query returned no row: " + sql);
            return rs.getString(1);
        }
    }

    private long singleLong(String sql) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next(), "query returned no row: " + sql);
            return rs.getLong(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
    }

    private MigrateResult migrate() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
