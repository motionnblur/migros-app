package com.example.MigrosBackend.service.user.profile;

import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
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

    /**
     * A missing user used to be dereferenced, so the caller got a
     * {@code NullPointerException} instead of the documented not-found response.
     */
    @Test
    void getUserProfileTable_MissingUserIsReportedAsNotFound() {
        when(userEntityRepository.findByUserMail(testEmail)).thenReturn(null);

        UserNotFoundException failure = assertThrows(
                UserNotFoundException.class,
                () -> userProfileService.getUserProfileTable(testEmail));

        assertEquals(testEmail, failure.getMessage());
    }

    /**
     * The write names the profile columns and never saves a whole entity. A
     * loaded {@code UserEntity} also carries the cart, the password hash and the
     * ban flag, so saving it back would restore all three as they were read and
     * silently undo whatever ran concurrently.
     */
    @Test
    void uploadUserProfileTable_WritesOnlyTheProfileColumns() {
        when(userEntityRepository.updateProfileColumnsByUserMail(
                testEmail, "Jane", "Smith", "123 Main St", "Apt 4",
                "Istanbul", "Turkey", "34000")).thenReturn(1);

        userProfileService.uploadUserProfileTable(
                "Jane", "Smith", "123 Main St", "Apt 4",
                "Istanbul", "Turkey", "34000", testEmail
        );

        verify(userEntityRepository, never()).save(any());
        verify(userEntityRepository, never()).findByUserMail(anyString());
        assertEquals("John", mockUser.getUserName(),
                "the service must not even load the row it is about to update");
    }

    @Test
    void uploadUserProfileTable_MissingUserIsReportedAsNotFound() {
        when(userEntityRepository.updateProfileColumnsByUserMail(
                anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0);

        UserNotFoundException failure = assertThrows(
                UserNotFoundException.class,
                () -> userProfileService.uploadUserProfileTable(
                        "Jane", "Smith", "123 Main St", "Apt 4",
                        "Istanbul", "Turkey", "34000", testEmail));

        assertEquals(testEmail, failure.getMessage());
    }
}
