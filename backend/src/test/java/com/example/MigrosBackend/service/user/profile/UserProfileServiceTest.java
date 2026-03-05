package com.example.MigrosBackend.service.user.profile;

import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.shared.InvalidTokenException;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {
    @Mock
    private UserEntityRepository userEntityRepository;
    @Mock
    private TokenService tokenService;

    @InjectMocks
    private UserProfileService userProfileService;

    private UserEntity mockUser;
    private final String testToken = "valid.jwt.token";
    private final String testEmail = "migrosuser@example.com";

    @BeforeEach
    void setUp() {
        mockUser = new UserEntity();
        mockUser.setUserMail(testEmail);
        mockUser.setUserName("John");
        mockUser.setUserLastName("Doe");
    }

    @Test
    void getUserProfileTable_Success() {
        // Arrange
        when(tokenService.extractUsername(testToken)).thenReturn(testEmail);
        when(tokenService.validateToken(testToken, testEmail)).thenReturn(true);
        when(userEntityRepository.findByUserMail(testEmail)).thenReturn(mockUser);

        // Act
        UserProfileTableDto result = userProfileService.getUserProfileTable(testToken);

        // Assert
        assertNotNull(result);
        assertEquals("John", result.getUserFirstName());
        assertEquals("Doe", result.getUserLastName());
        verify(tokenService).validateToken(testToken, testEmail);
    }

    @Test
    void uploadUserProfileTable_Success() {
        // Arrange
        when(tokenService.extractUsername(testToken)).thenReturn(testEmail);
        when(tokenService.validateToken(testToken, testEmail)).thenReturn(true);
        when(userEntityRepository.findByUserMail(testEmail)).thenReturn(mockUser);

        // Act
        userProfileService.uploadUserProfileTable(
                "Jane", "Smith", "123 Main St", "Apt 4",
                "Istanbul", "Turkey", "34000", testToken
        );

        // Assert
        assertEquals("Jane", mockUser.getUserName());
        assertEquals("Smith", mockUser.getUserLastName());
        assertEquals("34000", mockUser.getUserPostalCode());
        verify(userEntityRepository).save(mockUser);
    }

    @Test
    void shouldThrowInvalidTokenException_WhenTokenIsInvalid() {
        // Arrange
        when(tokenService.extractUsername(testToken)).thenReturn(testEmail);
        when(tokenService.validateToken(testToken, testEmail)).thenReturn(false);

        // Act & Assert
        assertThrows(InvalidTokenException.class, () ->
                userProfileService.getUserProfileTable(testToken)
        );
        verify(userEntityRepository, never()).findByUserMail(any());
    }
}