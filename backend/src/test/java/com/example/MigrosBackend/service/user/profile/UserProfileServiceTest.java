package com.example.MigrosBackend.service.user.profile;

import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
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

    @InjectMocks
    private UserProfileService userProfileService;

    private UserEntity mockUser;
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
        when(userEntityRepository.findByUserMail(testEmail)).thenReturn(mockUser);

        // Act
        UserProfileTableDto result = userProfileService.getUserProfileTable(testEmail);

        // Assert
        assertNotNull(result);
        assertEquals("John", result.getUserFirstName());
        assertEquals("Doe", result.getUserLastName());
        verify(userEntityRepository).findByUserMail(testEmail);
    }

    @Test
    void uploadUserProfileTable_Success() {
        // Arrange
        when(userEntityRepository.findByUserMail(testEmail)).thenReturn(mockUser);

        // Act
        userProfileService.uploadUserProfileTable(
                "Jane", "Smith", "123 Main St", "Apt 4",
                "Istanbul", "Turkey", "34000", testEmail
        );

        // Assert
        assertEquals("Jane", mockUser.getUserName());
        assertEquals("Smith", mockUser.getUserLastName());
        assertEquals("34000", mockUser.getUserPostalCode());
        verify(userEntityRepository).save(mockUser);
    }
}
