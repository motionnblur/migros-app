package com.example.MigrosBackend.migration;

import com.example.MigrosBackend.helper.ProductPricingPolicy;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V13 adds the materialized effective price that catalogue search filters and
 * orders by, and it has to land on both shapes of database: an empty one, where
 * every table is built by the migrations, and one that already holds products.
 *
 * <p>The upgraded case is the one that can fail quietly. The column has to be
 * added nullable, backfilled, and only then made {@code NOT NULL}; adding it
 * {@code NOT NULL} from the start either fails outright on a populated table or
 * silently leaves rows the search cannot compare - and a price filter that
 * quietly skips rows is worse than one that errors, because the customer is told
 * a band contains three products when it contains five.
 *
 * <p>The backfill is asserted against {@link ProductPricingPolicy} itself rather
 * than against hand-written expectations, because the whole point of the column
 * is that it holds that policy's number. The cases include the rounding
 * boundaries the policy exists for: a factor that rounds up, a factor that rounds
 * down, and a result whose half-way case has to go away from zero. Two copies of
 * a rounding sequence agree everywhere except exactly there.
 */
@Testcontainers
class ProductEffectivePriceMigrationPostgresTest {

    private static final String V12 = "12";

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
     * On an empty database the column has to exist with the exact declared type,
     * because Hibernate runs with {@code ddl-auto=validate} and refuses to start
     * when the mapping and the schema disagree.
     */
    @Test
    void aFreshDatabaseGetsTheColumnWithTheShapeTheEntityMappingValidates() throws SQLException {
        MigrateResult result = migrate();

        assertEquals(MigrationTestSupport.versionedMigrationCount(), result.migrationsExecuted,
                "a first migrate must apply every versioned script, including V13");
        assertEquals(1, singleLong("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version = '13' AND success"),
                "Flyway must record the effective-price migration on an empty database");
        assertColumnShape();
    }

    /**
     * A row inserted straight into the table, the way the other migration tests
     * do, must be a row the search path can actually filter. With a nullable
     * column left as it is after this migration, that insert either fails or
     * produces a row that no price comparison can match.
     */
    @Test
    void theColumnRejectsAnExplicitNullOnAFreshSchema() throws SQLException {
        migrate();
        assertColumnShape();

        assertThrows(SQLException.class,
                () -> execute("INSERT INTO product_entity (product_name, subcategory_name, product_count, "
                        + "product_price, product_discount, effective_price, product_description) "
                        + "VALUES ('No Effective Price', 'general', 1, 5.00, 0.00, NULL, 'desc')"),
                "a null effective price is a row every price filter silently skips");
    }

    @Test
    void aProductInsertedWithoutAnEffectivePriceIsStillRefused() throws SQLException {
        migrate();

        assertThrows(SQLException.class,
                () -> execute("INSERT INTO product_entity (product_name, subcategory_name, product_count, "
                        + "product_price, product_discount, product_description) "
                        + "VALUES ('Omitted', 'general', 1, 5.00, 0.00, 'desc')"),
                "every writer computes the column, so omitting it has to be a loud integrity error "
                        + "rather than a row that sorts and filters as if it had no price");
    }

    // -------------------------------------------------------------------------
    // Populated schema
    // -------------------------------------------------------------------------

    /**
     * The upgrade path. Rows written before the column existed have to be filled
     * from the policy's own arithmetic, because a search over an upgraded
     * database that orders by a column nothing backfilled is arbitrary.
     */
    @Test
    void anUpgradedDatabaseBackfillsEveryExistingRow() throws SQLException {
        migrateTo(V12);
        assertEquals(0, columnCount("product_entity", "effective_price"),
                "precondition: the column must not exist before V13");

        insertLegacyProduct("Plain Apple", "20.00", "0.00");
        insertLegacyProduct("Half Off Pear", "20.00", "50.00");
        insertLegacyProduct("Third Off Fig", "19.99", "33.33");
        insertLegacyProduct("Tiny Discount", "5.00", "0.01");

        migrate();

        assertColumnShape();
        for (String name : List.of("Plain Apple", "Half Off Pear", "Third Off Fig", "Tiny Discount")) {
            assertEquals(ProductPricingPolicy.effectivePrice(priceOf(name), discountOf(name)),
                    effectivePriceOf(name),
                    "an upgraded row must hold exactly what the catalogue displays for it");
        }
    }

    /**
     * The upgrade must not disturb anything else about the rows it touches.
     * A backfill that rewrites inventory or the pre-discount price is a far worse
     * outcome than a failed migration.
     */
    @Test
    void anUpgradedDatabaseKeepsTheColumnsTheBackfillDidNotMeanToTouch() throws SQLException {
        migrateTo(V12);
        long productId = insertLegacyProductWithStock("Legacy Apple", "12.00", "25.00", 7);

        migrate();

        assertEquals("12.00", rawPriceOf(productId));
        assertEquals("25.00", discountOf("Legacy Apple").toPlainString());
        assertEquals(7, singleLong("SELECT product_count FROM product_entity WHERE product_entity_id = "
                + productId));
        assertEquals(0, singleLong("SELECT version FROM product_entity WHERE product_entity_id = "
                + productId), "V13 must not touch the edit version");
    }

    /**
     * A database older than V1 could hold a null price or discount, because those
     * columns were nullable then and V1 only changed their type. The catalogue has
     * always rendered such a row at zero rather than failing, and the backfill
     * reproduces that tolerance so an old row does not become an unusable one.
     *
     * <p>That is exactly why V13 is three statements and not one: without the
     * {@code COALESCE}, or with the column made {@code NOT NULL} before the
     * backfill, this upgrade fails and takes the whole application down with it.
     *
     * <p>The legacy shape is hand-built for the same reason
     * {@code MoneyColumnsMigrationPostgresTest} builds one: on a schema produced by
     * the migrations alone the columns are already {@code NOT NULL}, so the
     * nullable case cannot be staged through them.
     */
    @Test
    void rowsWithNullPriceOrDiscountBackfillInsteadOfFailingTheUpgrade() throws SQLException {
        createLegacyNullableProductTable();
        insertLegacyRow("Null Price", null, "10.00");
        insertLegacyRow("Null Discount", "8.00", null);

        migrate();

        assertColumnShape();
        assertEquals(new BigDecimal("0.00"), effectivePriceOf("Null Price"),
                "a null price reads as zero, which is what the listing has always shown");
        assertEquals(new BigDecimal("8.00"), effectivePriceOf("Null Discount"),
                "a null discount reads as no discount, so the price stands");
    }

    /**
     * The pre-V1 shape: {@code REAL} money columns and no nullability, so the
     * conversion in V1 leaves them nullable and a row with a missing value can
     * survive all the way to V13.
     */
    private void createLegacyNullableProductTable() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE product_entity ("
                    + "product_entity_id BIGSERIAL PRIMARY KEY, "
                    + "product_name VARCHAR(255) NOT NULL, "
                    + "product_price REAL, "
                    + "product_discount REAL)");
        }
    }

    /**
     * Inserts a legacy row with either money column missing, which is the state
     * the {@code COALESCE} in the backfill exists for.
     */
    private void insertLegacyRow(String name, String price, String discount) throws SQLException {
        execute("INSERT INTO product_entity (product_name, product_price, product_discount) VALUES ("
                + "'" + name + "', " + nullable(price) + ", " + nullable(discount) + ")");
    }

    private String nullable(String literal) {
        return literal == null ? "NULL" : literal;
    }

    /**
     * A discount above 100 is rejected by application validation but not by the
     * schema, so a pre-existing row can hold one. The policy returns a negative
     * effective price for it and the catalogue shows that negative price, so the
     * column must too. This is also why V13 deliberately adds no CHECK
     * constraint: one would fail this upgrade, replacing a rendering quirk with a
     * database that will not start.
     */
    @Test
    void anOutOfRangeLegacyDiscountBackfillsToWhatThePolicyAlreadyComputed() throws SQLException {
        migrateTo(V12);
        insertLegacyProduct("Impossible Discount", "10.00", "150.00");

        migrate();

        assertEquals(ProductPricingPolicy.effectivePrice(new BigDecimal("10.00"), new BigDecimal("150.00")),
                effectivePriceOf("Impossible Discount"),
                "the column must agree with the policy even where the input is invalid, or the "
                        + "listing and the sort key would disagree about the same row");
        assertTrue(effectivePriceOf("Impossible Discount").signum() < 0,
                "sanity: the policy really does return a negative price here, and that is what is stored");
    }

    /**
     * The same arithmetic, verified row by row against the policy rather than
     * against hand-written expectations. The prices and discounts are chosen to sit
     * on the boundaries where a differently-ordered rounding sequence produces a
     * different cent.
     *
     * @param price    the stored pre-discount price
     * @param discount the stored discount percentage
     */
    @ParameterizedTest(name = "price {0} less {1}%")
    @CsvSource({
            "20.00, 50.00",
            "19.99, 33.33",
            "0.03, 33.33",
            "0.01, 50.00",
            "5.00, 0.01",
            "5.00, 33.33",
            "5.00, 66.67",
            "999.99, 7.77",
            "0.05, 1.00",
            "10.01, 3.33",
            "1.00, 99.99",
            "7.00, 12.50",
            "123.45, 7.50",
            "33.33, 3.00"
    })
    void theBackfillMatchesThePricingPolicyExactly(String price, String discount) throws SQLException {
        migrateTo(V12);
        String name = "boundary-" + price + "-" + discount;
        insertLegacyProduct(name, price, discount);

        migrate();

        BigDecimal stored = effectivePriceOf(name);
        BigDecimal policy = ProductPricingPolicy.effectivePrice(new BigDecimal(price), new BigDecimal(discount));

        assertEquals(0, stored.compareTo(policy),
                "backfill of price=" + price + " discount=" + discount + " produced " + stored
                        + " where the policy produces " + policy
                        + "; the factor must be rounded to six decimals before the multiply");
        assertEquals(2, stored.scale(), "a stored effective price is always at the money scale");
    }

    /**
     * The upgrade is safe to re-run: a rolled-back deploy that left V13 applied
     * must not fail the next startup.
     */
    @Test
    void reRunningTheMigrationChangesNothing() throws SQLException {
        migrateTo(V12);
        insertLegacyProduct("Idempotent Apple", "20.00", "25.00");

        migrate();
        migrate();

        assertEquals(ProductPricingPolicy.effectivePrice(new BigDecimal("20.00"), new BigDecimal("25.00")),
                effectivePriceOf("Idempotent Apple"));
        assertEquals("20.00", rawPriceOf(rawIdOf("Idempotent Apple")));
    }

    /**
     * The oldest shape the application actually supports: a {@code product_entity}
     * that predates every column V5 declares.
     *
     * <p>{@code CREATE TABLE IF NOT EXISTS} in V5 leaves such a table alone, so V5
     * reconciles it with {@code ADD COLUMN IF NOT EXISTS} for each column it
     * declares - which means the money columns arrive as <em>nullable</em> ones, with
     * null in the rows already there. That is the state the {@code COALESCE} exists
     * for, and it is also why this backfill is unconditional: by the time V13 runs
     * the columns are always present.
     */
    @Test
    void theOldestSupportedSchemaBackfillsRowsWhoseMoneyColumnsWereAddedAsNullable() throws SQLException {
        execute("CREATE TABLE product_entity ("
                + "product_entity_id BIGSERIAL PRIMARY KEY, "
                + "product_name VARCHAR(255) NOT NULL)");
        execute("INSERT INTO product_entity (product_name) VALUES ('Priceless')");

        migrate();

        assertEquals(0, columnCount("product_entity", "effective_price") - 1,
                "precondition: the column is added by this migration, not before it");
        assertColumnShape();
        assertEquals(new BigDecimal("0.00"), effectivePriceOf("Priceless"),
                "a row whose price column did not exist until V5 added it reads as zero, "
                        + "which is what the listing has always shown for it");
        assertEquals(1, singleLong("SELECT COUNT(*) FROM pg_indexes "
                + "WHERE tablename = 'product_entity' AND indexname = 'idx_product_effective_price'"),
                "the price index is created on this shape too");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertColumnShape() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT data_type, numeric_precision, numeric_scale, is_nullable, column_default "
                             + "FROM information_schema.columns WHERE table_schema = 'public' "
                             + "AND table_name = 'product_entity' AND column_name = 'effective_price'")) {
            assertTrue(rs.next(), "product_entity.effective_price must exist after V13");
            assertEquals("numeric", rs.getString("data_type"));
            assertEquals(19, rs.getInt("numeric_precision"));
            assertEquals(2, rs.getInt("numeric_scale"),
                    "the search compares against the price a card shows, which is always at the money scale");
            assertEquals("NO", rs.getString("is_nullable"),
                    "a nullable column is a row that no price comparison can match, and no ORDER BY can place");
            assertNull(rs.getString("column_default"),
                    "a default would have to encode the discount arithmetic, which is the second copy "
                            + "of the policy this column exists to remove");
        }

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'product_entity' "
                             + "AND indexname = 'idx_product_effective_price'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "the two price predicates and the two price orderings are served "
                    + "by this index");
        }
    }

    private void insertLegacyProduct(String name, String price, String discount) throws SQLException {
        execute("INSERT INTO product_entity (product_name, subcategory_name, product_count, "
                + "product_price, product_discount, product_description) VALUES ('" + name + "', "
                + "'general', 5, " + price + ", " + discount + ", 'migration guard')");
    }

    private long insertLegacyProductWithStock(String name, String price, String discount, int stock)
            throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "INSERT INTO product_entity (product_name, subcategory_name, product_count, "
                             + "product_price, product_discount, product_description) VALUES ('" + name
                             + "', 'general', " + stock + ", " + price + ", " + discount
                             + ", 'migration guard') RETURNING product_entity_id")) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private BigDecimal effectivePriceOf(String name) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT effective_price FROM product_entity WHERE product_name = '" + name + "'")) {
            assertTrue(rs.next(), "no product named " + name);
            return rs.getBigDecimal(1);
        }
    }

    private BigDecimal priceOf(String name) throws SQLException {
        return decimalColumnOf(name, "product_price");
    }

    private BigDecimal discountOf(String name) throws SQLException {
        return decimalColumnOf(name, "product_discount");
    }

    private BigDecimal decimalColumnOf(String name, String column) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT " + column + " FROM product_entity WHERE product_name = '" + name + "'")) {
            assertTrue(rs.next(), "no product named " + name);
            return rs.getBigDecimal(1);
        }
    }

    private long rawIdOf(String name) throws SQLException {
        return singleLong("SELECT product_entity_id FROM product_entity WHERE product_name = '" + name + "'");
    }

    private String rawPriceOf(long productId) throws SQLException {
        return decimalString("SELECT product_price FROM product_entity WHERE product_entity_id = " + productId);
    }

    private String decimalString(String sql) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next(), "query returned no row: " + sql);
            return rs.getBigDecimal(1).toPlainString();
        }
    }

    private long columnCount(String table, String column) throws SQLException {
        return singleLong("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = '" + table + "' AND column_name = '" + column + "'");
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
