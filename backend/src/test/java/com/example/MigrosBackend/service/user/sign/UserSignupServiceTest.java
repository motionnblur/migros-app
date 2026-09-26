package com.example.MigrosBackend.service.user.sign;

import com.example.MigrosBackend.config.PublicUrlProperties;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.thymeleaf.context.Context;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserSignupServiceTest {

    private static final String BACKEND_BASE_URL = "https://api.shop.example";
    private static final String FRONTEND_BASE_URL = "https://shop.example";
    private static final String SIGNUP_EMAIL = "test@example.com";
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
        signupDto.setUserMail(SIGNUP_EMAIL);
        signupDto.setUserPassword("StrongPass123!");
        userSignupService = serviceWith(BACKEND_BASE_URL, FRONTEND_BASE_URL);
    }

    private UserSignupService serviceWith(String backendBaseUrl, String frontendBaseUrl) {
        PublicUrlProperties publicUrlProperties = new PublicUrlProperties();
        publicUrlProperties.setBackendBaseUrl(backendBaseUrl);
        publicUrlProperties.setFrontendBaseUrl(frontendBaseUrl);
        return new UserSignupService(
                userEntityRepository,
                pendingSignupEntityRepository,
                encryptService,
                mailService,
                tokenService,
                passwordValidator,
                publicUrlProperties,
                15
        );
    }

    private String lastConfirmationLink() throws MessagingException {
        ArgumentCaptor<Context> contextCaptor = ArgumentCaptor.forClass(Context.class);
        verify(mailService, atLeastOnce())
                .sendMimeMessage(anyString(), anyString(), anyString(), contextCaptor.capture());
        return (String) contextCaptor.getValue().getVariable("confirmationLink");
    }

    private String savedPendingToken() {
        ArgumentCaptor<PendingSignupEntity> pendingCaptor = ArgumentCaptor.forClass(PendingSignupEntity.class);
        verify(pendingSignupEntityRepository, atLeastOnce()).save(pendingCaptor.capture());
        return pendingCaptor.getValue().getToken();
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
        when(tokenService.generateUserToken(signupDto.getUserMail())).thenReturn("jwt_token_xyz");

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

    @Test
    void signupConfirmationLinkUsesBackendPublicBaseUrl() throws Exception {
        when(userEntityRepository.existsByUserMail(signupDto.getUserMail())).thenReturn(false);
        when(passwordValidator.isPasswordStrongEnough(signupDto.getUserPassword())).thenReturn(true);
        when(encryptService.getEncryptedPassword(anyString())).thenReturn("hashed_password");

        userSignupService.signup(signupDto);

        String token = savedPendingToken();
        String confirmationLink = lastConfirmationLink();

        assertEquals(BACKEND_BASE_URL + "/user/signup/confirm?token=" + token, confirmationLink);
        assertFalse(confirmationLink.contains(FRONTEND_BASE_URL));
        assertFalse(confirmationLink.contains("localhost"));
    }

    @Test
    void passwordResetLinkUsesFrontendPublicBaseUrl() throws Exception {
        UserEntity existingUser = new UserEntity();
        existingUser.setUserMail(SIGNUP_EMAIL);
        existingUser.setUserPassword("hashed_password");
        when(userEntityRepository.findByUserMail(SIGNUP_EMAIL)).thenReturn(existingUser);

        userSignupService.verifyUserMail(SIGNUP_EMAIL);

        String token = savedPendingToken();
        String resetLink = lastConfirmationLink();

        assertEquals(FRONTEND_BASE_URL + "/reset-password/" + token, resetLink);
        assertFalse(resetLink.contains(BACKEND_BASE_URL));
        assertFalse(resetLink.contains("localhost"));
    }

    @Test
    void linkGenerationNormalizesOneTrailingSlash() throws Exception {
        UserSignupService service = serviceWith(BACKEND_BASE_URL + "/", FRONTEND_BASE_URL + "/");

        when(userEntityRepository.existsByUserMail(signupDto.getUserMail())).thenReturn(false);
        when(passwordValidator.isPasswordStrongEnough(signupDto.getUserPassword())).thenReturn(true);
        when(encryptService.getEncryptedPassword(anyString())).thenReturn("hashed_password");
        UserEntity existingUser = new UserEntity();
        existingUser.setUserMail(SIGNUP_EMAIL);
        existingUser.setUserPassword("hashed_password");
        when(userEntityRepository.findByUserMail(SIGNUP_EMAIL)).thenReturn(existingUser);

        service.signup(signupDto);
        String signupToken = savedPendingToken();
        String signupLink = lastConfirmationLink();

        service.verifyUserMail(SIGNUP_EMAIL);
        String resetToken = savedPendingToken();
        String resetLink = lastConfirmationLink();

        assertEquals(BACKEND_BASE_URL + "/user/signup/confirm?token=" + signupToken, signupLink);
        assertEquals(FRONTEND_BASE_URL + "/reset-password/" + resetToken, resetLink);
        assertFalse(signupLink.substring("https://".length()).contains("//"), signupLink);
        assertFalse(resetLink.substring("https://".length()).contains("//"), resetLink);
    }

    @Test
    void publicBaseUrlsRejectBlankMalformedOrNonHttpValues() {
        List<String> invalidValues = List.of(
                "",
                "   ",
                "shop.example",
                "api.shop.example",
                "ftp://shop.example",
                "//shop.example",
                "https://user:pass@shop.example",
                "https://shop.example/path",
                "https://shop.example/path/",
                "https://shop.example?query=1",
                "https://shop.example#fragment",
                "https://:8080"
        );

        for (String invalid : invalidValues) {
            PublicUrlProperties badBackend = new PublicUrlProperties();
            badBackend.setBackendBaseUrl(invalid);
            badBackend.setFrontendBaseUrl(FRONTEND_BASE_URL);
            IllegalStateException backendFailure = assertThrows(
                    IllegalStateException.class, badBackend::validate, "expected backend rejection for: " + invalid);
            assertTrue(backendFailure.getMessage().contains("app.backend-base-url"),
                    "failure must name app.backend-base-url but was: " + backendFailure.getMessage());

            PublicUrlProperties badFrontend = new PublicUrlProperties();
            badFrontend.setFrontendBaseUrl(invalid);
            badFrontend.setBackendBaseUrl(BACKEND_BASE_URL);
            IllegalStateException frontendFailure = assertThrows(
                    IllegalStateException.class, badFrontend::validate, "expected frontend rejection for: " + invalid);
            assertTrue(frontendFailure.getMessage().contains("app.frontend-base-url"),
                    "failure must name app.frontend-base-url but was: " + frontendFailure.getMessage());
        }

        PublicUrlProperties valid = new PublicUrlProperties();
        valid.setBackendBaseUrl(BACKEND_BASE_URL);
        valid.setFrontendBaseUrl(FRONTEND_BASE_URL);
        assertDoesNotThrow(valid::validate);
    }

    @Test
    void productionProfileRequiresBothPublicOrigins() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(PublicUrlProperties.class);

        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("app.backend-base-url=" + BACKEND_BASE_URL)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(deepestMessage(context.getStartupFailure()))
                            .contains("app.frontend-base-url")
                            .doesNotContain("secret");
                });

        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("app.frontend-base-url=" + FRONTEND_BASE_URL)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(deepestMessage(context.getStartupFailure()))
                            .contains("app.backend-base-url")
                            .doesNotContain("secret");
                });
    }

    private static String deepestMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? "" : current.getMessage();
    }
}
