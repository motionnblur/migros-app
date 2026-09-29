package com.example.MigrosBackend.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MailFromStartupValidationTest {

    @Test
    void missingFromFailsStartupOutsideExactLocal() {
        AdminStartupProfilePolicy policy = mock(AdminStartupProfilePolicy.class);
        when(policy.isLocalDevelopment()).thenReturn(false);

        MailFromStartupValidation validation =
                new MailFromStartupValidation(policy, "  ");

        assertThrows(IllegalStateException.class, validation::validate);
    }

    @Test
    void configuredFromStartsOutsideLocal() {
        AdminStartupProfilePolicy policy = mock(AdminStartupProfilePolicy.class);
        when(policy.isLocalDevelopment()).thenReturn(false);

        MailFromStartupValidation validation =
                new MailFromStartupValidation(policy, "no-reply@shop.example");

        assertDoesNotThrow(validation::validate);
    }

    @Test
    void missingFromIsAllowedInExactLocal() {
        AdminStartupProfilePolicy policy = mock(AdminStartupProfilePolicy.class);
        when(policy.isLocalDevelopment()).thenReturn(true);

        MailFromStartupValidation validation =
                new MailFromStartupValidation(policy, "");

        assertDoesNotThrow(validation::validate);
    }
}
