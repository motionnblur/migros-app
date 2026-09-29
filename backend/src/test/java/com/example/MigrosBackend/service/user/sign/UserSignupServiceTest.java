package com.example.MigrosBackend.service.user.sign;

import com.example.MigrosBackend.config.PublicUrlProperties;
import com.example.MigrosBackend.dto.user.sign.ResetPasswordDto;
import com.example.MigrosBackend.dto.user.sign.UserSignDto;
import com.example.MigrosBackend.entity.user.PendingSignupEntity;
import com.example.MigrosBackend.entity.user.PendingTokenPurpose;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mail.MailSendException;
import org.springframework.transaction.PlatformTransactionManager;
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
    @Mock
    private PlatformTransactionManager transactionManager;

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
                transactionManager,
                15
        );
    }

    private String lastConfirmationLink() throws MessagingException {
        ArgumentCaptor<Context> contextCaptor = ArgumentCaptor.forClass(Context.class);
        verify(mailService, atLeastOnce())
                .sendMimeMessage(anyString(), anyString(), anyString(), contextCaptor.capture());
        return (String) contextCaptor.getValue().getVariable("confirmationLink");
    }

    private PendingSignupEntity savedPendingSignup() {
        ArgumentCaptor<PendingSignupEntity> pendingCaptor = ArgumentCaptor.forClass(PendingSignupEntity.class);
        verify(pendingSignupEntityRepository, atLeastOnce()).save(pendingCaptor.capture());
        return pendingCaptor.getValue();
    }

    private String savedPendingToken() {
        return savedPendingSignup().getToken();
    }

    private static PendingSignupEntity pendingToken(String token, PendingTokenPurpose purpose, LocalDateTime expiresAt) {
        return new PendingSignupEntity(token, "test@mail.com", "hashed_password", expiresAt, purpose);
    }

    private UserEntity existingUser() {
        UserEntity user = new UserEntity();
        user.setUserMail(SIGNUP_EMAIL);
        user.setUserPassword("hashed_password");
        return user;
    }

    @Test
    void signup_Success() throws Exception {
        when(userEntityRepository.existsByUserMail(signupDto.getUserMail())).thenReturn(false);
        when(passwordValidator.isPasswordStrongEnough(signupDto.getUserPassword())).thenReturn(true);
        when(encryptService.getEncryptedPassword(anyString())).thenReturn("hashed_password");

        userSignupService.signup(signupDto);

        verify(encryptService).getEncryptedPassword(signupDto.getUserPassword());
        verify(pendingSignupEntityRepository).deleteByUserMailAndTokenPurpose(
                signupDto.getUserMail(), PendingTokenPurpose.SIGNUP);
        verify(pendingSignupEntityRepository).save(any(PendingSignupEntity.class));
        verify(mailService).sendMimeMessage(eq(signupDto.getUserMail()), anyString(), anyString(), any(Context.class));
    }

    @Test
    void signup_StoresTheTokenBeforeSendingTheMail() throws Exception {
        when(userEntityRepository.existsByUserMail(signupDto.getUserMail())).thenReturn(false);
        when(passwordValidator.isPasswordStrongEnough(signupDto.getUserPassword())).thenReturn(true);
        when(encryptService.getEncryptedPassword(anyString())).thenReturn("hashed_password");

        userSignupService.signup(signupDto);

        InOrder order = inOrder(pendingSignupEntityRepository, mailService);
        order.verify(pendingSignupEntityRepository).save(any(PendingSignupEntity.class));
        order.verify(mailService).sendMimeMessage(eq(SIGNUP_EMAIL), anyString(), anyString(), any(Context.class));
    }

    @Test
    void signup_PersistsTheTokenWithTheSignupPurpose() {
        when(userEntityRepository.existsByUserMail(signupDto.getUserMail())).thenReturn(false);
        when(passwordValidator.isPasswordStrongEnough(signupDto.getUserPassword())).thenReturn(true);
        when(encryptService.getEncryptedPassword(anyString())).thenReturn("hashed_password");

        userSignupService.signup(signupDto);

        assertEquals(PendingTokenPurpose.SIGNUP, savedPendingSignup().getTokenPurpose());
    }

    @Test
    void signup_PropagatesDatabaseFailureInsteadOfAcceptingAProcessLocalToken() throws MessagingException {
        when(userEntityRepository.existsByUserMail(signupDto.getUserMail())).thenReturn(false);
        when(passwordValidator.isPasswordStrongEnough(signupDto.getUserPassword())).thenReturn(true);
        when(encryptService.getEncryptedPassword(anyString())).thenReturn("hashed_password");
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(pendingSignupEntityRepository).save(any(PendingSignupEntity.class));

        assertThrows(DataAccessResourceFailureException.class, () -> userSignupService.signup(signupDto));

        verify(mailService, never()).sendMimeMessage(anyString(), anyString(), anyString(), any(Context.class));
    }

    @Test
    void verifyUserMail_PropagatesDatabaseFailureAndSendsNoMail() throws MessagingException {
        when(userEntityRepository.findByUserMail(SIGNUP_EMAIL)).thenReturn(existingUser());
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(pendingSignupEntityRepository).save(any(PendingSignupEntity.class));

        assertThrows(DataAccessResourceFailureException.class, () -> userSignupService.verifyUserMail(SIGNUP_EMAIL));

        verify(mailService, never()).sendMimeMessage(anyString(), anyString(), anyString(), any(Context.class));
    }

    @Test
    void confirm_RejectsAPasswordResetToken() {
        when(pendingSignupEntityRepository.findById("reset-token"))
                .thenReturn(Optional.of(pendingToken(
                        "reset-token", PendingTokenPurpose.PASSWORD_RESET, LocalDateTime.now().plusMinutes(10))));

        assertThrows(TokenNotFoundException.class, () -> userSignupService.confirm("reset-token"));

        verify(userEntityRepository, never()).save(any(UserEntity.class));
        verify(pendingSignupEntityRepository, never()).deleteById(anyString());
    }

    @Test
    void confirmUserMail_RejectsAPasswordResetToken() {
        when(pendingSignupEntityRepository.findById("reset-token"))
                .thenReturn(Optional.of(pendingToken(
                        "reset-token", PendingTokenPurpose.PASSWORD_RESET, LocalDateTime.now().plusMinutes(10))));

        assertThrows(TokenNotFoundException.class, () -> userSignupService.confirmUserMail("reset-token"));
    }

    @Test
    void resetPassword_RejectsASignupTokenAndLeavesTheAccountUntouched() {
        when(pendingSignupEntityRepository.findById("signup-token"))
                .thenReturn(Optional.of(pendingToken(
                        "signup-token", PendingTokenPurpose.SIGNUP, LocalDateTime.now().plusMinutes(10))));

        ResetPasswordDto dto = new ResetPasswordDto();
        dto.setToken("signup-token");
        dto.setUserPassword("AnotherStrong123!");

        assertThrows(TokenNotFoundException.class, () -> userSignupService.resetPassword(dto));

        verify(userEntityRepository, never()).save(any(UserEntity.class));
        verify(pendingSignupEntityRepository, never()).deleteById(anyString());
    }

    @Test
    void resetPassword_ReplacesThePasswordForAResetToken() {
        when(pendingSignupEntityRepository.findById("reset-token"))
                .thenReturn(Optional.of(pendingToken(
                        "reset-token", PendingTokenPurpose.PASSWORD_RESET, LocalDateTime.now().plusMinutes(10))));
        when(userEntityRepository.findByUserMail("test@mail.com")).thenReturn(existingUser());
        when(passwordValidator.isPasswordStrongEnough("AnotherStrong123!")).thenReturn(true);
        when(encryptService.getEncryptedPassword("AnotherStrong123!")).thenReturn("new_hash");

        ResetPasswordDto dto = new ResetPasswordDto();
        dto.setToken("reset-token");
        dto.setUserPassword("AnotherStrong123!");

        userSignupService.resetPassword(dto);

        verify(userEntityRepository).save(argThat(user -> "new_hash".equals(user.getUserPassword())));
        verify(pendingSignupEntityRepository).deleteById("reset-token");
    }

    @Test
    void resetPassword_DeletesAndRejectsAnExpiredResetToken() {
        when(pendingSignupEntityRepository.findById("reset-token"))
                .thenReturn(Optional.of(pendingToken(
                        "reset-token", PendingTokenPurpose.PASSWORD_RESET, LocalDateTime.now().minusMinutes(1))));

        ResetPasswordDto dto = new ResetPasswordDto();
        dto.setToken("reset-token");
        dto.setUserPassword("AnotherStrong123!");

        assertThrows(TokenNotFoundException.class, () -> userSignupService.resetPassword(dto));

        verify(pendingSignupEntityRepository).deleteById("reset-token");
        verify(userEntityRepository, never()).save(any(UserEntity.class));
    }

    @Test
    void resetPassword_RejectsABlankToken() {
        ResetPasswordDto dto = new ResetPasswordDto();
        dto.setToken("  ");
        dto.setUserPassword("AnotherStrong123!");

        assertThrows(TokenNotFoundException.class, () -> userSignupService.resetPassword(dto));
        verify(pendingSignupEntityRepository, never()).findById(anyString());
    }

    @Test
    void signup_ThrowsException_WhenUserAlreadyExists() throws MessagingException {
        when(userEntityRepository.existsByUserMail(signupDto.getUserMail())).thenReturn(true);

        assertThrows(UserAlreadyExistsException.class, () -> userSignupService.signup(signupDto));
        verify(mailService, never()).sendMimeMessage(any(), any(), any(), any());
    }

    @Test
    void signup_ThrowsException_WhenPasswordIsWeak() {
        when(userEntityRepository.existsByUserMail(signupDto.getUserMail())).thenReturn(false);
        when(passwordValidator.isPasswordStrongEnough(signupDto.getUserPassword())).thenReturn(false);

        assertThrows(WeakPasswordException.class, () -> userSignupService.signup(signupDto));
    }

    @Test
    void signup_ThrowsException_WhenMailServiceFails() throws MessagingException {
        when(userEntityRepository.existsByUserMail(signupDto.getUserMail())).thenReturn(false);
        when(passwordValidator.isPasswordStrongEnough(signupDto.getUserPassword())).thenReturn(true);
        when(encryptService.getEncryptedPassword(anyString())).thenReturn("hashed_password");

        doThrow(new MessagingException("SMTP error"))
                .when(mailService)
                .sendMimeMessage(anyString(), anyString(), anyString(), any());

        assertThrows(MailSendingFailedException.class, () -> {
            userSignupService.signup(signupDto);
        });

        String issuedToken = savedPendingToken();
        verify(pendingSignupEntityRepository).deleteById(issuedToken);
    }

    /**
     * Spring reports most mail failures unchecked.
     *
     * <p>{@code JavaMailSenderImpl.send} wraps an SMTP refusal in
     * {@code MailSendException}, and an unassemblable message becomes a
     * {@code MailPreparationException}; both are {@code MailException} and
     * neither is a {@code MessagingException}. Catching only the checked
     * exception let the most common failure of all escape as an unexpected
     * runtime error, which skipped the revocation and left a confirmation token
     * nobody ever received redeemable for its whole lifetime - while the caller
     * saw a raw framework error instead of the mail-failure response.
     */
    @Test
    void signup_RevokesTheCommittedTokenAndReportsTheMailFailure_WhenTheSenderFailsUnchecked()
            throws MessagingException {
        when(userEntityRepository.existsByUserMail(signupDto.getUserMail())).thenReturn(false);
        when(passwordValidator.isPasswordStrongEnough(signupDto.getUserPassword())).thenReturn(true);
        when(encryptService.getEncryptedPassword(anyString())).thenReturn("hashed_password");

        doThrow(new MailSendException("SMTP connection refused"))
                .when(mailService)
                .sendMimeMessage(anyString(), anyString(), anyString(), any());

        MailSendingFailedException failure = assertThrows(MailSendingFailedException.class,
                () -> userSignupService.signup(signupDto));

        assertEquals("Mail sending failed. Please try again later.", failure.getMessage(),
                "an unchecked mail failure must produce the same response as a checked one");

        String issuedToken = savedPendingToken();
        verify(pendingSignupEntityRepository).deleteById(issuedToken);
    }

    @Test
    void verifyUserMail_RevokesTheCommittedTokenAndReportsTheMailFailure_WhenTheSenderFailsUnchecked()
            throws MessagingException {
        when(userEntityRepository.findByUserMail(SIGNUP_EMAIL)).thenReturn(existingUser());

        doThrow(new MailSendException("SMTP connection refused"))
                .when(mailService)
                .sendMimeMessage(anyString(), anyString(), anyString(), any());

        MailSendingFailedException failure = assertThrows(MailSendingFailedException.class,
                () -> userSignupService.verifyUserMail(SIGNUP_EMAIL));

        assertEquals("Mail sending failed. Please try again later.", failure.getMessage());

        String issuedToken = savedPendingToken();
        verify(pendingSignupEntityRepository).deleteById(issuedToken);
    }

    @Test
    void login_Success_ReturnsToken() {
        UserEntity existingUser = new UserEntity();
        existingUser.setUserMail(signupDto.getUserMail());
        existingUser.setUserPassword("hashed_password");

        when(userEntityRepository.findByUserMail(signupDto.getUserMail())).thenReturn(existingUser);
        when(encryptService.checkIfPasswordMatches(signupDto.getUserPassword(), "hashed_password")).thenReturn(true);
        when(tokenService.generateUserToken(signupDto.getUserMail())).thenReturn("jwt_token_xyz");

        String token = userSignupService.login(signupDto);

        assertEquals("jwt_token_xyz", token);
    }

    @Test
    void login_ThrowsException_WhenPasswordWrong() {
        UserEntity existingUser = new UserEntity();
        when(userEntityRepository.findByUserMail(signupDto.getUserMail())).thenReturn(existingUser);
        when(encryptService.checkIfPasswordMatches(anyString(), any())).thenReturn(false);

        assertThrows(WrongPasswordException.class, () -> userSignupService.login(signupDto));
    }

    @Test
    void confirm_ThrowsException_WhenTokenInvalid() {
        assertThrows(TokenNotFoundException.class, () -> userSignupService.confirm("invalid-token-123"));
    }

    @Test
    void confirm_Success_Coverage() {
        String testToken = "test-token-123";
        PendingSignupEntity pendingSignupEntity = pendingToken(
                testToken, PendingTokenPurpose.SIGNUP, LocalDateTime.now().plusMinutes(10));

        when(pendingSignupEntityRepository.findById(testToken)).thenReturn(Optional.of(pendingSignupEntity));

        userSignupService.confirm(testToken);

        verify(userEntityRepository, times(1)).save(any(UserEntity.class));
        verify(pendingSignupEntityRepository, times(1)).deleteById(testToken);
    }

    @Test
    void confirm_ThrowsException_WhenTokenExpired() {
        String testToken = "expired-token";
        PendingSignupEntity expiredToken = pendingToken(
                testToken, PendingTokenPurpose.SIGNUP, LocalDateTime.now().minusMinutes(1));

        when(pendingSignupEntityRepository.findById(testToken)).thenReturn(Optional.of(expiredToken));

        assertThrows(TokenNotFoundException.class, () -> userSignupService.confirm(testToken));
        verify(pendingSignupEntityRepository).deleteById(testToken);
    }

    @Test
    void confirm_TokenNotFound_Coverage() {
        assertThrows(TokenNotFoundException.class, () -> {
            userSignupService.confirm("wrong-token");
        });
    }

    @Test
    void confirmUserMail_SucceedsForAnUnexpiredSignupToken() {
        when(pendingSignupEntityRepository.findById("signup-token"))
                .thenReturn(Optional.of(pendingToken(
                        "signup-token", PendingTokenPurpose.SIGNUP, LocalDateTime.now().plusMinutes(10))));

        userSignupService.confirmUserMail("signup-token");

        verify(pendingSignupEntityRepository, never()).deleteById(anyString());
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
        UserEntity existing = existingUser();
        when(userEntityRepository.findByUserMail(SIGNUP_EMAIL)).thenReturn(existing);

        userSignupService.verifyUserMail(SIGNUP_EMAIL);

        String token = savedPendingToken();
        String resetLink = lastConfirmationLink();

        assertEquals(FRONTEND_BASE_URL + "/reset-password/" + token, resetLink);
        assertFalse(resetLink.contains(BACKEND_BASE_URL));
        assertFalse(resetLink.contains("localhost"));
    }

    @Test
    void passwordResetTokenIsPersistedWithTheResetPurpose() {
        when(userEntityRepository.findByUserMail(SIGNUP_EMAIL)).thenReturn(existingUser());

        userSignupService.verifyUserMail(SIGNUP_EMAIL);

        assertEquals(PendingTokenPurpose.PASSWORD_RESET, savedPendingSignup().getTokenPurpose());
        verify(pendingSignupEntityRepository).deleteByUserMailAndTokenPurpose(
                SIGNUP_EMAIL, PendingTokenPurpose.PASSWORD_RESET);
    }

    @Test
    void linkGenerationNormalizesOneTrailingSlash() throws Exception {
        UserSignupService service = serviceWith(BACKEND_BASE_URL + "/", FRONTEND_BASE_URL + "/");

        when(userEntityRepository.existsByUserMail(signupDto.getUserMail())).thenReturn(false);
        when(passwordValidator.isPasswordStrongEnough(signupDto.getUserPassword())).thenReturn(true);
        when(encryptService.getEncryptedPassword(anyString())).thenReturn("hashed_password");
        when(userEntityRepository.findByUserMail(SIGNUP_EMAIL)).thenReturn(existingUser());

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
