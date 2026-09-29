package com.example.MigrosBackend.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves V10 reproduces the legacy category seeder exactly.
 *
 * <p>The 18 names and their legacy {@code category_id} values (1..18) were
 * previously inserted by a {@code CommandLineRunner}; this test freezes the
 * migrated result and proves a re-run (or a first run against a partially
 * populated database) cannot duplicate a name.
 */
@Testcontainers
class CategorySeedMigrationPostgresTest {

    private static final Object[][] EXPECTED_CATEGORIES = {
            {1, "Yılbaşı"},
            {2, "Meyve, Sebze"},
            {3, "Süt, Kahvaltılık"},
            {4, "Temel Gıda"},
            {5, "Meze, Hazır yemek, Donut"},
            {6, "İçecek"},
            {7, "Dondurma"},
            {8, "Atistirmalik"},
            {9, "Fırın, Pastane"},
            {10, "Deterjan, Temizlik"},
            {11, "Kağıt, Islak mendil"},
            {12, "Kişisel Bakım,Kozmetik, Sağlık"},
            {13, "Bebek"},
            {14, "Ev, Yaşam"},
            {15, "Kitap, Kırtasiye, Oyuncak"},
            {16, "Çiçek"},
            {17, "Pet Shop"},
            {18, "Elektronik"}
    };

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

    @Test
    void freshDatabaseIsSeededWithTheExactNamesAndIds() throws SQLException {
        migrate();

        Map<Integer, String> expected = new LinkedHashMap<>();
        for (Object[] row : EXPECTED_CATEGORIES) {
            expected.put((Integer) row[0], (String) row[1]);
        }

        assertEquals(expected, categoriesById());
    }

    @Test
    void aFreshDatabaseHasExactlyEighteenCategories() throws SQLException {
        migrate();

        assertEquals(18, rowCount());
    }

    @Test
    void secondMigrateDoesNotDuplicateTheSeed() throws SQLException {
        migrate();

        MigrateResult second = migrate();
        assertEquals(0, second.migrationsExecuted, "a second migrate must have nothing left to apply");
        assertEquals(18, rowCount(), "the seed must not be duplicated by a re-run");
    }

    @Test
    void existingCategoryNamesAreSkippedAndTheirIdsPreserved() throws SQLException {
        migrateTo("9");

        execute("INSERT INTO category_entity (category_id, category_name) VALUES (99, 'Elektronik')");
        execute("INSERT INTO category_entity (category_id, category_name) VALUES (77, 'Custom Category')");

        MigrateResult result = migrate();
        assertEquals(1, result.migrationsExecuted, "only V10 should still be pending");

        assertEquals(1, countByName("Elektronik"), "an already-present name must not be inserted twice");
        assertEquals(99, categoryIdByName("Elektronik"), "a pre-existing category_id must be preserved");
        assertEquals(1, countByName("Custom Category"), "unrelated pre-existing rows must be untouched");
        assertEquals(19, rowCount(), "17 missing seed names plus the 2 pre-existing rows");
    }

    private Map<Integer, String> categoriesById() throws SQLException {
        Map<Integer, String> categories = new LinkedHashMap<>();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT category_id, category_name FROM category_entity ORDER BY category_id")) {
            while (rs.next()) {
                categories.put(rs.getInt("category_id"), rs.getString("category_name"));
            }
        }
        return categories;
    }

    private int rowCount() throws SQLException {
        return (int) singleLong("SELECT COUNT(*) FROM category_entity");
    }

    private int countByName(String name) throws SQLException {
        return (int) singleLong("SELECT COUNT(*) FROM category_entity WHERE category_name = '" + name.replace("'", "''") + "'");
    }

    private int categoryIdByName(String name) throws SQLException {
        return (int) singleLong("SELECT category_id FROM category_entity WHERE category_name = '" + name.replace("'", "''") + "'");
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
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
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
