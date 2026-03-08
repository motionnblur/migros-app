package com.example.MigrosBackend.service.global;

import com.example.MigrosBackend.config.security.SecurityConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EncryptServiceTest {
    @Mock
    private SecurityConfiguration securityConfiguration;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private EncryptService encryptService;

    @BeforeEach
    void setUp() {
        // Since the service calls this method in every function,
        // we stub it here to return our mocked encoder.
        lenient().when(securityConfiguration.passwordEncoder()).thenReturn(passwordEncoder);
    }

    @Test
    void getEncryptedPassword_ShouldReturnEncodedString() {
        // Arrange
        String rawPassword = "mySecretPassword";
        String expectedHash = "encoded_hash_123";
        when(passwordEncoder.encode(rawPassword)).thenReturn(expectedHash);

        // Act
        String result = encryptService.getEncryptedPassword(rawPassword);

        // Assert
        assertEquals(expectedHash, result);
        verify(passwordEncoder, times(1)).encode(rawPassword);
    }

    @Test
    void checkIfPasswordMatches_ShouldReturnTrue_WhenPasswordsMatch() {
        // Arrange
        String rawPassword = "mySecretPassword";
        String encodedPassword = "encoded_hash_123";
        when(passwordEncoder.matches(rawPassword, encodedPassword)).thenReturn(true);

        // Act
        boolean matches = encryptService.checkIfPasswordMatches(rawPassword, encodedPassword);

        // Assert
        assertTrue(matches);
        verify(passwordEncoder, times(1)).matches(rawPassword, encodedPassword);
    }

    @Test
    void checkIfPasswordMatches_ShouldReturnFalse_WhenPasswordsDoNotMatch() {
        // Arrange
        String rawPassword = "wrongPassword";
        String encodedPassword = "encoded_hash_123";
        when(passwordEncoder.matches(rawPassword, encodedPassword)).thenReturn(false);

        // Act
        boolean matches = encryptService.checkIfPasswordMatches(rawPassword, encodedPassword);

        // Assert
        assertFalse(matches);
        verify(passwordEncoder, times(1)).matches(rawPassword, encodedPassword);
    }
}
