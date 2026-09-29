package com.example.MigrosBackend.service.user.sign;

import com.example.MigrosBackend.dto.user.sign.ResetPasswordDto;
import com.example.MigrosBackend.dto.user.sign.UserSignDto;
import com.example.MigrosBackend.entity.user.PendingSignupEntity;
import com.example.MigrosBackend.entity.user.PendingTokenPurpose;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.shared.TokenNotFoundException;
import com.example.MigrosBackend.exception.user.MailSendingFailedException;
import com.example.MigrosBackend.exception.user.UserMailNotFoundException;
import com.example.MigrosBackend.repository.user.PendingSignupEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.EncryptService;
import com.example.MigrosBackend.service.global.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.thymeleaf.context.Context;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pending signup/reset tokens must be durable and single-purpose against a real
 * PostgreSQL schema, not only against mocks.
 *
 * <p>{@code PendingSignupEntityRepository} is spied rather than replaced: the
 * purpose column, the check constraint, and the per-mail replacement all have to
 * be exercised against the real table, and the outage scenarios need the write
 * to fail on demand.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class PendingTokenPostgresTest {

    private static final String USER_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String ADMIN_SECRET = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";
    private static final String MAIL = "signup@migros.com";
    private static final String STRONG_PASSWORD = "StrongPass123!";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("jwt.user-secret", () -> USER_SECRET);
        registry.add("jwt.admin-secret", () -> ADMIN_SECRET);
        registry.add("support.internal.key", () -> "integration-test-internal-key");
        registry.add("support.service.internal-key", () -> "integration-test-internal-key");
        registry.add("app.frontend-base-url", () -> "http://localhost:4200");
        registry.add("app.backend-base-url", () -> "http://localhost:8080");
    }

    @MockitoSpyBean
    private PendingSignupEntityRepository pendingSignupEntityRepository;
    @MockitoBean
    private MailService mailService;
    @MockitoBean
    private EncryptService encryptService;

    @Autowired
    private UserEntityRepository userEntityRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private UserSignupService userSignupService;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE pending_signup_entity, user_entity RESTART IDENTITY CASCADE");
    }

    @Test
    void signupPersistsADurableSignupPurposeTokenBeforeMailIsSent() throws Exception {
        when(encryptService.getEncryptedPassword(STRONG_PASSWORD)).thenReturn("hashed");

        userSignupService.signup(signupDto(MAIL));

        List<PendingSignupEntity> stored = pendingSignupEntityRepository.findAll();
        assertEquals(1, stored.size());
        assertEquals(PendingTokenPurpose.SIGNUP, stored.get(0).getTokenPurpose());
        assertNotNull(stored.get(0).getExpiresAt());
        assertTrue(stored.get(0).getExpiresAt().isAfter(LocalDateTime.now()));
        verify(mailService, times(1)).sendMimeMessage(anyString(), anyString(), anyString(), any(Context.class));
    }

    @Test
    void aResetTokenCannotConfirmASignup() {
        seedToken("reset-token", PendingTokenPurpose.PASSWORD_RESET);

        assertThrows(TokenNotFoundException.class, () -> userSignupService.confirm("reset-token"));
        assertThrows(TokenNotFoundException.class, () -> userSignupService.confirmUserMail("reset-token"));

        assertTrue(userEntityRepository.findByUserMail(MAIL) == null,
                "a reset token must never create an account");
        assertTrue(pendingSignupEntityRepository.findById("reset-token").isPresent(),
                "a rejected cross-purpose attempt must not consume the token");
    }

    @Test
    void aSignupTokenCannotResetAPassword() {
        seedUserWithPassword("original-hash");
        seedToken("signup-token", PendingTokenPurpose.SIGNUP);

        ResetPasswordDto dto = new ResetPasswordDto();
        dto.setToken("signup-token");
        dto.setUserPassword("AnotherStrong123!");

        assertThrows(TokenNotFoundException.class, () -> userSignupService.resetPassword(dto));

        assertEquals("original-hash", storedPassword(),
                "a signup token must never be usable to choose a new password");
        assertTrue(pendingSignupEntityRepository.findById("signup-token").isPresent());
    }

    @Test
    void aSignupTokenAndAResetTokenForTheSameMailCoexist() {
        seedUserWithPassword("original-hash");
        seedToken("signup-token", PendingTokenPurpose.SIGNUP);
        seedToken("reset-token", PendingTokenPurpose.PASSWORD_RESET);

        assertEquals(2, pendingSignupEntityRepository.count());

        when(encryptService.getEncryptedPassword("AnotherStrong123!")).thenReturn("rotated-hash");
        ResetPasswordDto dto = new ResetPasswordDto();
        dto.setToken("reset-token");
        dto.setUserPassword("AnotherStrong123!");

        userSignupService.resetPassword(dto);

        assertEquals("rotated-hash", storedPassword());
        assertTrue(pendingSignupEntityRepository.findById("signup-token").isPresent(),
                "consuming a reset token must not silently invalidate an unrelated signup token");
    }

    @Test
    void issuingASecondResetTokenReplacesOnlyThePreviousResetToken() throws Exception {
        seedToken("signup-token", PendingTokenPurpose.SIGNUP);
        seedUserWithPassword("original-hash");
        when(encryptService.getEncryptedPassword(anyString())).thenReturn("original-hash");

        userSignupService.verifyUserMail(MAIL);

        assertTrue(pendingSignupEntityRepository.findById("signup-token").isPresent());
        assertEquals(2, pendingSignupEntityRepository.count());
    }

    @Test
    void aUsedTokenIsDeletedAndCannotBeReplayed() {
        seedToken("signup-token", PendingTokenPurpose.SIGNUP);

        userSignupService.confirm("signup-token");

        assertFalse(pendingSignupEntityRepository.findById("signup-token").isPresent());
        assertThrows(TokenNotFoundException.class, () -> userSignupService.confirm("signup-token"));
    }

    @Test
    void aStorageFailureFailsTheRequestInsteadOfIssuingAProcessLocalToken() throws Exception {
        when(encryptService.getEncryptedPassword(STRONG_PASSWORD)).thenReturn("hashed");
        doThrow(new DataAccessResourceFailureException("simulated storage outage"))
                .when(pendingSignupEntityRepository).save(any(PendingSignupEntity.class));

        assertThrows(DataAccessResourceFailureException.class,
                () -> userSignupService.signup(signupDto(MAIL)));

        verify(mailService, never()).sendMimeMessage(anyString(), anyString(), anyString(), any(Context.class));
        assertTrue(userEntityRepository.findByUserMail(MAIL) == null);
    }

    @Test
    void aMailFailureLeavesNoUndeliverableTokenBehind() throws Exception {
        when(encryptService.getEncryptedPassword(STRONG_PASSWORD)).thenReturn("hashed");
        doThrow(new jakarta.mail.MessagingException("SMTP down")).when(mailService)
                .sendMimeMessage(anyString(), anyString(), anyString(), any(Context.class));

        assertThrows(MailSendingFailedException.class, () -> userSignupService.signup(signupDto(MAIL)));

        assertEquals(0, pendingSignupEntityRepository.count(),
                "a token nobody was ever told about must not be left in the table");
    }

    /**
     * The token must be durable <em>before</em> the mail leaves.
     *
     * <p>The read is taken over its own JDBC connection in autocommit mode, so
     * it observes committed rows only. Anything routed through the repository
     * would join the transaction that is open on this thread and would happily
     * see a row that is merely staged - which is exactly the state the test
     * exists to rule out. A delivered link must reference a token that a later
     * rollback, or a crash, cannot remove.
     */
    @Test
    void theSignupTokenIsAlreadyCommittedWhenTheConfirmationMailIsSent() throws Exception {
        when(encryptService.getEncryptedPassword(STRONG_PASSWORD)).thenReturn("hashed");

        List<String> visibleWhenMailWasSent = new ArrayList<>();
        doAnswer(invocation -> {
            visibleWhenMailWasSent.addAll(committedTokensOnAnIndependentConnection());
            return null;
        }).when(mailService).sendMimeMessage(anyString(), anyString(), anyString(), any(Context.class));

        userSignupService.signup(signupDto(MAIL));

        assertEquals(1, visibleWhenMailWasSent.size(),
                "the mail went out before the token was committed, so the link could reference nothing");
        assertTrue(visibleWhenMailWasSent.get(0).contains("SIGNUP"), visibleWhenMailWasSent.get(0));
    }

    @Test
    void theResetTokenIsAlreadyCommittedWhenTheResetMailIsSent() throws Exception {
        seedUserWithPassword("original-hash");

        List<String> visibleWhenMailWasSent = new ArrayList<>();
        doAnswer(invocation -> {
            visibleWhenMailWasSent.addAll(committedTokensOnAnIndependentConnection());
            return null;
        }).when(mailService).sendMimeMessage(anyString(), anyString(), anyString(), any(Context.class));

        userSignupService.verifyUserMail(MAIL);

        assertEquals(1, visibleWhenMailWasSent.size());
        assertTrue(visibleWhenMailWasSent.get(0).contains("PASSWORD_RESET"), visibleWhenMailWasSent.get(0));
    }

    /** Tokens another transaction could see, read over a connection of its own. */
    private List<String> committedTokensOnAnIndependentConnection() throws Exception {
        List<String> tokens = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT token || ':' || token_purpose FROM pending_signup_entity")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    tokens.add(resultSet.getString(1));
                }
            }
        }
        return tokens;
    }

    @Test
    void aRevokedTokenStaysRevokedAfterItsIssuingRequestReturned() throws Exception {
        when(encryptService.getEncryptedPassword(STRONG_PASSWORD)).thenReturn("hashed");
        doThrow(new jakarta.mail.MessagingException("SMTP down")).when(mailService)
                .sendMimeMessage(anyString(), anyString(), anyString(), any(Context.class));

        assertThrows(MailSendingFailedException.class, () -> userSignupService.signup(signupDto(MAIL)));

        // Read from a fresh transaction: the revocation has to be committed too,
        // or it disappears with whatever request happened to carry it.
        assertEquals(0, pendingSignupEntityRepository.count());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pending_signup_entity", Integer.class));
    }

    @Test
    void aRevokedResetTokenIsRemovedForLaterRequestsAsWell() throws Exception {
        seedUserWithPassword("original-hash");
        doThrow(new jakarta.mail.MessagingException("SMTP down")).when(mailService)
                .sendMimeMessage(anyString(), anyString(), anyString(), any(Context.class));

        assertThrows(MailSendingFailedException.class, () -> userSignupService.verifyUserMail(MAIL));

        assertEquals(0, pendingSignupEntityRepository.count());
    }

    @Test
    void aResetRequestForAnUnknownMailFailsWithoutIssuingAToken() {
        assertThrows(UserMailNotFoundException.class,
                () -> userSignupService.verifyUserMail("nobody@migros.com"));

        assertEquals(0, pendingSignupEntityRepository.count());
    }

    @Test
    void expiredTokensAreRejectedAndRemoved() {
        pendingSignupEntityRepository.saveAndFlush(new PendingSignupEntity(
                "expired-token", MAIL, "hashed", LocalDateTime.now().minusMinutes(1),
                PendingTokenPurpose.SIGNUP));

        assertThrows(TokenNotFoundException.class, () -> userSignupService.confirm("expired-token"));

        assertFalse(pendingSignupEntityRepository.findById("expired-token").isPresent());
    }

    @Test
    void theMigrationRejectsAPurposeOutsideTheAllowedSet() {
        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "INSERT INTO pending_signup_entity "
                        + "(token, user_mail, user_password, expires_at, token_purpose) "
                        + "VALUES ('bad-purpose', ?, 'hash', now() + interval '1 hour', 'NOT_A_PURPOSE')",
                MAIL));
    }

    @Test
    void thePurposeColumnIsNotNullable() {
        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "INSERT INTO pending_signup_entity (token, user_mail, user_password, expires_at) "
                        + "VALUES ('no-purpose', ?, 'hash', now() + interval '1 hour')",
                MAIL));
    }

    @Test
    void theMigrationLeftNoPurposeLessLegacyRowRedeemable() {
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pending_signup_entity WHERE token_purpose IS NULL", Integer.class));
    }

    /**
     * Two callers must not be able to redeem one token into two accounts.
     *
     * <p>Both threads are released at the same moment and call the same
     * endpoint. The conditional DELETE lets exactly one of them remove the row;
     * the other is reported as "token not found". The unique mailbox constraint
     * is the second line of defense, but the assertion here is the end state:
     * one account, token consumed.
     */
    @Test
    void onlyOneConcurrentConfirmationOfTheSameTokenSucceeds() throws Exception {
        seedToken("race-token", PendingTokenPurpose.SIGNUP);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> confirmOnce = () -> {
                start.await();
                try {
                    userSignupService.confirm("race-token");
                    return true;
                } catch (TokenNotFoundException ex) {
                    return false;
                }
            };
            Future<Boolean> first = pool.submit(confirmOnce);
            Future<Boolean> second = pool.submit(confirmOnce);
            start.countDown();

            int successes = (first.get(30, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(30, TimeUnit.SECONDS) ? 1 : 0);

            assertEquals(1, successes, "exactly one confirmation may win the token");
            assertEquals(1, jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM user_entity WHERE user_mail = ?", Integer.class, MAIL),
                    "one token must never create two accounts");
            assertFalse(pendingSignupEntityRepository.findById("race-token").isPresent(),
                    "the winning redemption must consume the token");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Two concurrent signups for the same mailbox must not leave two live
     * tokens. The database's unique (user_mail, token_purpose) constraint makes
     * the loser of the race fail loudly instead of silently duplicating the
     * token.
     */
    @Test
    void onlyOneConcurrentSignupForTheSameMailboxLeavesOneToken() throws Exception {
        when(encryptService.getEncryptedPassword(anyString())).thenReturn("hashed");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> signupOnce = () -> {
                start.await();
                try {
                    userSignupService.signup(signupDto(MAIL));
                    return true;
                } catch (RuntimeException ex) {
                    return false;
                }
            };
            Future<Boolean> first = pool.submit(signupOnce);
            Future<Boolean> second = pool.submit(signupOnce);
            start.countDown();

            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);

            assertEquals(1, jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM pending_signup_entity WHERE user_mail = ? AND token_purpose = ?",
                            Integer.class, MAIL, PendingTokenPurpose.SIGNUP.name()),
                    "a mailbox must never hold two live tokens for the same purpose");
        } finally {
            pool.shutdownNow();
        }
    }

    private UserSignDto signupDto(String mail) {
        UserSignDto dto = new UserSignDto();
        dto.setUserMail(mail);
        dto.setUserPassword(STRONG_PASSWORD);
        return dto;
    }

    private void seedToken(String token, PendingTokenPurpose purpose) {
        pendingSignupEntityRepository.saveAndFlush(new PendingSignupEntity(
                token, MAIL, "hashed", LocalDateTime.now().plusMinutes(15), purpose));
    }

    private void seedUserWithPassword(String password) {
        UserEntity user = new UserEntity();
        user.setUserMail(MAIL);
        user.setUserPassword(password);
        userEntityRepository.saveAndFlush(user);
    }

    private String storedPassword() {
        return userEntityRepository.findByUserMail(MAIL).getUserPassword();
    }
}
