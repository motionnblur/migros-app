package com.example.MigrosBackend.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StripeWebhookStartupValidationTest {

    @Test
    void missingSecretFailsStartupOutsideExactLocal() {
        AdminStartupProfilePolicy policy = mock(AdminStartupProfilePolicy.class);
        when(policy.isLocalDevelopment()).thenReturn(false);

        StripeWebhookStartupValidation validation =
                new StripeWebhookStartupValidation(policy, "  ");

        assertThrows(IllegalStateException.class, validation::validate);
    }

    @Test
    void configuredSecretStartsOutsideLocal() {
        AdminStartupProfilePolicy policy = mock(AdminStartupProfilePolicy.class);
        when(policy.isLocalDevelopment()).thenReturn(false);

        StripeWebhookStartupValidation validation =
                new StripeWebhookStartupValidation(policy, "whsec_configured");

        assertDoesNotThrow(validation::validate);
    }

    @Test
    void missingSecretIsAllowedInExactLocal() {
        AdminStartupProfilePolicy policy = mock(AdminStartupProfilePolicy.class);
        when(policy.isLocalDevelopment()).thenReturn(true);

        StripeWebhookStartupValidation validation =
                new StripeWebhookStartupValidation(policy, "");

        assertDoesNotThrow(validation::validate);
    }
}
