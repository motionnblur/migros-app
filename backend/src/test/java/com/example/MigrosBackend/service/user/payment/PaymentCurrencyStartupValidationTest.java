package com.example.MigrosBackend.service.user.payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentCurrencyStartupValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withBean(PaymentAmountConverter.class);

    @Test
    void contextStartsWithSupportedTryCurrency() {
        runner.withPropertyValues("payment.currency=try")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PaymentAmountConverter.class);
                });
    }

    @Test
    void contextFailsToStartForUnsupportedCurrency() {
        runner.withPropertyValues("payment.currency=usd")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void contextFailsToStartForBlankCurrency() {
        runner.withPropertyValues("payment.currency=")
                .run(context -> assertThat(context).hasFailed());
    }
}
