package com.example.MigrosBackend.service.user.sign;

import com.example.MigrosBackend.dto.user.sign.UserSignDto;
import com.example.MigrosBackend.entity.user.PendingSignupEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.shared.TokenNotFoundException;
import com.example.MigrosBackend.exception.shared.WrongPasswordException;
import com.example.MigrosBackend.exception.user.MailSendingFailedException;
import com.example.MigrosBackend.exception.user.UserAlreadyExistsException;
import com.example.MigrosBackend.exception.user.WeakPasswordException;
import com.example.MigrosBackend.helper.PasswordValidator;
import com.example.MigrosBackend.repository.user.PendingSignupEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.EncryptService;
import com.example.MigrosBackend.service.global.MailService;
import com.example.MigrosBackend.service.global.TokenService;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.context.Context;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserSignupServiceTest {
    @Mock
    private UserEntityRepository userEntityRepository;
    @Mock
    private PendingSignupEntityRepository pendingSignupEntityRepository;
    @Mock
    private EncryptService encryptService;
    @Mock
    private MailService mailService;
    @Mock
    private TokenService tokenService;
    @Mock
    private PasswordValidator passwordValidator;

    private UserSignupService userSignupService;

    private UserSignDto signupDto;

    @BeforeEach
    void setUp() {
        signupDto = new UserSignDto();
        signupDto.setUserMail("test@example.com");
        signupDto.setUserPassword("StrongPass123!");
        userSignupService = new UserSignupService(
                userEntityRepository,
                pendingSignupEntityRepository,
                encryptService,
                mailService,
                tokenService,
                passwordValidator,
                "http://localhost:4200",
                15
        );
    }

    @Test
    void signup_Success() throws Exception {
        // Arrange
        when(userEntityRepository.existsByUserMail(signupDto.getUserMail())).thenReturn(false);
        when(passwordValidator.isPasswordStrongEnough(signupDto.getUserPassword())).thenReturn(true);
        when(encryptService.getEncryptedPassword(anyString())).thenReturn("hashed_password");

        // Act
        userSignupService.signup(signupDto);

        // Assert
        verify(encryptService).getEncryptedPassword(signupDto.getUserPassword());
        verify(pendingSignupEntityRepository).deleteByUserMail(signupDto.getUserMail());
        verify(pendingSignupEntityRepository).save(any(PendingSignupEntity.class));
        verify(mailService).sendMimeMessage(eq(signupDto.getUserMail()), anyString(), anyString(), any(Context.class));
    }

    @Test
    void signup_ThrowsException_WhenUserAlreadyExists() throws MessagingException {
        // Arrange
        when(userEntityRepository.existsByUserMail(signupDto.getUserMail())).thenReturn(true);

        // Act & Assert
        assertThrows(UserAlreadyExistsException.class, () -> userSignupService.signup(signupDto));
        verify(mailService, never()).sendMimeMessage(any(), any(), any(), any());
    }

    @Test
    void signup_ThrowsException_WhenPasswordIsWeak() {
        // Arrange
        when(userEntityRepository.existsByUserMail(signupDto.getUserMail())).thenReturn(false);
        when(passwordValidator.isPasswordStrongEnough(signupDto.getUserPassword())).thenReturn(false);

        // Act & Assert
        assertThrows(WeakPasswordException.class, () -> userSignupService.signup(signupDto));
    }

    @Test
    void signup_ThrowsException_WhenMailServiceFails() throws MessagingException {
        // Arrange
        when(userEntityRepository.existsByUserMail(signupDto.getUserMail())).thenReturn(false);
        when(passwordValidator.isPasswordStrongEnough(signupDto.getUserPassword())).thenReturn(true);
        when(encryptService.getEncryptedPassword(anyString())).thenReturn("hashed_password");

        doThrow(new MessagingException("SMTP error"))
                .when(mailService)
                .sendMimeMessage(anyString(), anyString(), anyString(), any());

        // Act & Assert
        assertThrows(MailSendingFailedException.class, () -> {
            userSignupService.signup(signupDto);
        });
    }

    @Test
    void login_Success_ReturnsToken() {
        // Arrange
        UserEntity existingUser = new UserEntity();
        existingUser.setUserMail(signupDto.getUserMail());
        existingUser.setUserPassword("hashed_password");

        when(userEntityRepository.findByUserMail(signupDto.getUserMail())).thenReturn(existingUser);
        when(encryptService.checkIfPasswordMatches(signupDto.getUserPassword(), "hashed_password")).thenReturn(true);
        when(tokenService.generateToken(signupDto.getUserMail())).thenReturn("jwt_token_xyz");

        // Act
        String token = userSignupService.login(signupDto);

        // Assert
        assertEquals("jwt_token_xyz", token);
    }

    @Test
    void login_ThrowsException_WhenPasswordWrong() {
        // Arrange
        UserEntity existingUser = new UserEntity();
        when(userEntityRepository.findByUserMail(signupDto.getUserMail())).thenReturn(existingUser);
        when(encryptService.checkIfPasswordMatches(anyString(), any())).thenReturn(false);

        // Act & Assert
        assertThrows(WrongPasswordException.class, () -> userSignupService.login(signupDto));
    }

    @Test
    void confirm_ThrowsException_WhenTokenInvalid() {
        // Act & Assert
        assertThrows(TokenNotFoundException.class, () -> userSignupService.confirm("invalid-token-123"));
    }

    @Test
    void confirm_Success_Coverage() {
        String testToken = "test-token-123";
        PendingSignupEntity pendingSignupEntity = new PendingSignupEntity(
                testToken,
                "test@mail.com",
                "hashed_password",
                LocalDateTime.now().plusMinutes(10)
        );

        when(pendingSignupEntityRepository.findById(testToken)).thenReturn(Optional.of(pendingSignupEntity));

        userSignupService.confirm(testToken);

        verify(userEntityRepository, times(1)).save(any(UserEntity.class));
        verify(pendingSignupEntityRepository, times(1)).deleteById(testToken);
    }

    @Test
    void confirm_ThrowsException_WhenTokenExpired() {
        String testToken = "expired-token";
        PendingSignupEntity expiredToken = new PendingSignupEntity(
                testToken,
                "test@mail.com",
                "hashed_password",
                LocalDateTime.now().minusMinutes(1)
        );

        when(pendingSignupEntityRepository.findById(testToken)).thenReturn(Optional.of(expiredToken));

        assertThrows(TokenNotFoundException.class, () -> userSignupService.confirm(testToken));
        verify(pendingSignupEntityRepository).deleteById(testToken);
    }

    @Test
    void confirm_TokenNotFound_Coverage() {
        // Act & Assert (This ensures the 'else' block is covered)
        assertThrows(TokenNotFoundException.class, () -> {
            userSignupService.confirm("wrong-token");
        });
    }
}
