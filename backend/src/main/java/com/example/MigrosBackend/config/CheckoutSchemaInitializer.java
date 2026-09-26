package com.example.MigrosBackend.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ensures the one-live-checkout-per-user rule is enforced in PostgreSQL even on a
 * brand-new empty database, where Flyway V2 is intentionally skipped because the
 * base schema is created by Hibernate afterwards.
 *
 * The partial unique index cannot be expressed with JPA annotations, so this
 * runner applies it idempotently once the Hibernate schema export has finished.
 * On existing databases Flyway V2 already created the index and this is a no-op.
 */
@Component
public class CheckoutSchemaInitializer implements ApplicationRunner {

    private static final String CREATE_LIVE_CHECKOUT_INDEX = """
            CREATE UNIQUE INDEX IF NOT EXISTS uq_checkout_live_per_user
            ON checkout_entity (user_entity_id)
            WHERE status IN ('PREPARED', 'PAYMENT_PROCESSING')
            """;

    private final JdbcTemplate jdbcTemplate;

    public CheckoutSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute(CREATE_LIVE_CHECKOUT_INDEX);
    }
}
