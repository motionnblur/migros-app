package com.example.MigrosBackend.helper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class PasswordValidatorTest {
    private final PasswordValidator validator = new PasswordValidator();

    @Test
    void isPasswordStrongEnough_ShouldReturnTrue_ForValidStrongPassword() {
        // Arrange & Act
        boolean result = validator.isPasswordStrongEnough("Strong123!");

        // Assert
        assertTrue(result, "Should accept a password with all required criteria");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Short1!",         // Too short (< 8)
            "alllowercase1!",  // No uppercase
            "ALLUPPERCASE1!",  // No lowercase
            "NoNumber!!",      // No digits
            "NoSpecialChar1"   // No special characters
    })
    void isPasswordStrongEnough_ShouldReturnFalse_ForWeakPasswords(String weakPassword) {
        // Act
        boolean result = validator.isPasswordStrongEnough(weakPassword);

        // Assert
        assertFalse(result, "Should reject password: " + weakPassword);
    }

    @Test
    void isPasswordStrongEnough_ShouldHandleEmptyString() {
        // Act & Assert
        assertFalse(validator.isPasswordStrongEnough(""));
    }
}