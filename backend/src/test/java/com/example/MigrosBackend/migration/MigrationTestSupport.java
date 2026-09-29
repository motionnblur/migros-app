package com.example.MigrosBackend.migration;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;

/**
 * Shared helpers for the Flyway migration tests.
 */
final class MigrationTestSupport {

    private MigrationTestSupport() {
    }

    /**
     * The number of versioned migration scripts on the classpath.
     *
     * <p>Tests assert how many scripts a first migrate applies. Hard-coding that
     * number meant every new migration broke several unrelated tests, which
     * trains contributors to update counts instead of reading the failure.
     * Counting the real scripts keeps the assertion meaningful (a first migrate
     * must apply all of them) without becoming a tripwire for the next
     * migration.
     */
    static int versionedMigrationCount() {
        try {
            Resource[] scripts = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/migration/V*__*.sql");
            return scripts.length;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to enumerate the Flyway migration scripts", ex);
        }
    }
}
