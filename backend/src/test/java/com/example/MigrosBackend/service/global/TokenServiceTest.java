package com.example.MigrosBackend.service.global;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenServiceTest {
    private TokenService tokenService;
    private final String testUsername = "migros_admin";

    @BeforeEach
    void setUp() {
        tokenService = new TokenService();
    }

    @Test
    void generateToken_ShouldReturnValidString() {
        // Act
        String token = tokenService.generateToken(testUsername);

        // Assert
        assertNotNull(token);
        assertFalse(token.isEmpty());
        // A JWT usually has two dots separating the three parts
        assertEquals(2, token.chars().filter(ch -> ch == '.').count());
    }

    @Test
    void extractUsername_ShouldReturnCorrectUsername() {
        // Arrange
        String token = tokenService.generateToken(testUsername);

        // Act
        String extracted = tokenService.extractUsername(token);

        // Assert
        assertEquals(testUsername, extracted);
    }

    @Test
    void validateToken_ShouldReturnTrue_ForValidToken() {
        // Arrange
        String token = tokenService.generateToken(testUsername);

        // Act
        boolean isValid = tokenService.validateToken(token, testUsername);

        // Assert
        assertTrue(isValid);
    }

    @Test
    void validateToken_ShouldReturnFalse_ForWrongUsername() {
        // Arrange
        String token = tokenService.generateToken(testUsername);

        // Act
        boolean isValid = tokenService.validateToken(token, "wrong_user");

        // Assert
        assertFalse(isValid);
    }

    @Test
    void isTokenExpired_ShouldReturnFalse_ImmediatelyAfterGeneration() {
        // Arrange
        String token = tokenService.generateToken(testUsername);

        // Act
        boolean isExpired = tokenService.isTokenExpired(token);

        // Assert
        assertFalse(isExpired);
    }
}