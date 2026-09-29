package com.example.MigrosBackend.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UploadDirStartupValidationTest {

    @Test
    void missingUploadDirFailsStartupOutsideExactLocal() {
        AdminStartupProfilePolicy policy = mock(AdminStartupProfilePolicy.class);
        when(policy.isLocalDevelopment()).thenReturn(false);

        UploadDirStartupValidation validation =
                new UploadDirStartupValidation(policy, "  ");

        assertThrows(IllegalStateException.class, validation::validate);
    }

    @Test
    void configuredUploadDirStartsOutsideLocal() {
        AdminStartupProfilePolicy policy = mock(AdminStartupProfilePolicy.class);
        when(policy.isLocalDevelopment()).thenReturn(false);

        UploadDirStartupValidation validation =
                new UploadDirStartupValidation(policy, "/var/data/migros-uploads");

        assertDoesNotThrow(validation::validate);
    }

    @Test
    void missingUploadDirIsAllowedInExactLocal() {
        AdminStartupProfilePolicy policy = mock(AdminStartupProfilePolicy.class);
        when(policy.isLocalDevelopment()).thenReturn(true);

        UploadDirStartupValidation validation =
                new UploadDirStartupValidation(policy, "");

        assertDoesNotThrow(validation::validate);
    }
}
