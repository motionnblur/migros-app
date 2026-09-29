package com.example.MigrosBackend.service.global;

import com.example.MigrosBackend.exception.shared.InvalidTokenException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class TokenServiceTest {
    private static final String USER_VALUE = "user-secret-value-that-is-long-enough-1234567890";
    private static final String ADMIN_VALUE = "admin-secret-value-that-is-long-enough-0987654321";
    private static final String FOREIGN_VALUE = "foreign-secret-value-that-is-long-enough-abcdefghij";

    private static final String USER_SECRET = base64(USER_VALUE);
    private static final String ADMIN_SECRET = base64(ADMIN_VALUE);
    private static final String FOREIGN_SECRET = base64(FOREIGN_VALUE);
    private static final String SHORT_SECRET = base64("too-short");

    private static final String IDENTICAL_VALUE = "identical-secret-value-long-enough-1234567890";
    private static final String IDENTICAL_SECRET = base64(IDENTICAL_VALUE);

    private static final String NOT_BASE64_SECRET = "this secret is definitely not base64 at all !!!";
    private static final String PLACEHOLDER_USER_SECRET = "replace-with-a-random-base64-user-secret";
    private static final String PLACEHOLDER_ADMIN_SECRET =
            "replace-with-a-different-random-base64-admin-secret";

    private final String testUsername = "migros_admin";

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService(USER_SECRET, ADMIN_SECRET, Clock.systemDefaultZone());
    }

    @Test
    void generateUserToken_ShouldReturnValidString() {
        String token = tokenService.generateUserToken(testUsername);

        assertNotNull(token);
        assertFalse(token.isEmpty());
        assertEquals(2, token.chars().filter(ch -> ch == '.').count());
    }

    @Test
    void generateAdminToken_ShouldReturnValidString() {
        String token = tokenService.generateAdminToken(testUsername);

        assertNotNull(token);
        assertFalse(token.isEmpty());
        assertEquals(2, token.chars().filter(ch -> ch == '.').count());
    }

    @Test
    void userToken_ValidatesWithUserValidator() {
        String token = tokenService.generateUserToken(testUsername);

        assertEquals(testUsername, tokenService.validateAndExtractUser(token));
    }

    @Test
    void adminToken_ValidatesWithAdminValidator() {
        String token = tokenService.generateAdminToken(testUsername);

        assertEquals(testUsername, tokenService.validateAndExtractAdmin(token));
    }

    @Test
    void userToken_FailsAdminValidation_EvenWhenSubjectIsAdmin() {
        String token = tokenService.generateUserToken("admin");

        assertThrows(InvalidTokenException.class, () -> tokenService.validateAndExtractAdmin(token));
    }

    @Test
    void adminToken_FailsUserValidation() {
        String token = tokenService.generateAdminToken(testUsername);

        assertThrows(InvalidTokenException.class, () -> tokenService.validateAndExtractUser(token));
    }

    @Test
    void tokenWithMissingSessionType_FailsValidation() {
        String token = signedToken(testUsername, null, USER_SECRET, future());

        assertThrows(InvalidTokenException.class, () -> tokenService.validateAndExtractUser(token));
    }

    @Test
    void tokenWithWrongSessionType_FailsValidation() {
        String token = signedToken(testUsername, TokenService.ADMIN_SESSION_TYPE, USER_SECRET, future());

        assertThrows(InvalidTokenException.class, () -> tokenService.validateAndExtractUser(token));
    }

    @Test
    void tokenSignedWithDifferentKey_FailsValidation() {
        String token = signedToken(testUsername, TokenService.USER_SESSION_TYPE, FOREIGN_SECRET, future());

        assertThrows(InvalidTokenException.class, () -> tokenService.validateAndExtractUser(token));
    }

    @Test
    void expiredToken_FailsValidation() {
        String token = signedToken(testUsername, TokenService.USER_SESSION_TYPE, USER_SECRET,
                new Date(System.currentTimeMillis() - 1000));

        assertThrows(InvalidTokenException.class, () -> tokenService.validateAndExtractUser(token));
    }

    @Test
    void constructor_Throws_WhenSecretsAreMissing() {
        assertThrows(IllegalStateException.class, () -> new TokenService(null, ADMIN_SECRET, fixedClock()));
        assertThrows(IllegalStateException.class, () -> new TokenService(USER_SECRET, null, fixedClock()));
        assertThrows(IllegalStateException.class, () -> new TokenService("", ADMIN_SECRET, fixedClock()));
        assertThrows(IllegalStateException.class, () -> new TokenService(USER_SECRET, "  ", fixedClock()));
    }

    @Test
    void constructor_Throws_WhenSecretIsTooShort() {
        assertThrows(IllegalStateException.class, () -> new TokenService(SHORT_SECRET, ADMIN_SECRET, fixedClock()));
        assertThrows(IllegalStateException.class, () -> new TokenService(USER_SECRET, SHORT_SECRET, fixedClock()));
    }

    @Test
    void constructor_Throws_WhenSecretsAreIdentical() {
        assertThrows(IllegalStateException.class,
                () -> new TokenService(IDENTICAL_SECRET, IDENTICAL_SECRET, fixedClock()));
    }

    @Test
    void constructor_Throws_WhenSecretIsNotValidBase64() {
        assertThrows(IllegalStateException.class, () -> new TokenService(NOT_BASE64_SECRET, ADMIN_SECRET, fixedClock()));
        assertThrows(IllegalStateException.class, () -> new TokenService(USER_SECRET, NOT_BASE64_SECRET, fixedClock()));
    }

    @Test
    void constructor_Throws_ForUserPlaceholderSecret() {
        assertThrows(IllegalStateException.class,
                () -> new TokenService(PLACEHOLDER_USER_SECRET, ADMIN_SECRET, fixedClock()));
    }

    @Test
    void constructor_Throws_ForAdminPlaceholderSecret() {
        assertThrows(IllegalStateException.class,
                () -> new TokenService(USER_SECRET, PLACEHOLDER_ADMIN_SECRET, fixedClock()));
    }

    @Test
    void constructor_AcceptsBase64UrlSecrets() {
        TokenService urlTokenService = new TokenService(
                base64Url(repeatedBytes(0x11)), base64Url(repeatedBytes(0x22)), fixedClock());

        String token = urlTokenService.generateUserToken(testUsername);

        assertEquals(testUsername, urlTokenService.validateAndExtractUser(token));
    }

    @Test
    void genericTokenApis_AreNotExposed() {
        boolean hasGenericApi = java.util.Arrays.stream(TokenService.class.getMethods())
                .anyMatch(method -> method.getName().equals("extractUsername")
                        || method.getName().equals("validateToken")
                        || method.getName().equals("generateToken"));

        assertFalse(hasGenericApi, "Generic token APIs must not remain available");
    }

    @Test
    void getTokenTtlMillis_ShouldReturnThreeMinutes() {
        assertEquals(1000L * 60 * 3, tokenService.getTokenTtlMillis());
    }

    @Test
    void token_ExpiresWhenTheInjectedClockAdvancesPastTheTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
        TokenService clockedService = new TokenService(USER_SECRET, ADMIN_SECRET, clock);

        String token = clockedService.generateUserToken(testUsername);

        clock.advance(Duration.ofMinutes(3).minusSeconds(1));
        assertEquals(testUsername, clockedService.validateAndExtractUser(token),
                "the token must still be valid one second before the TTL");

        clock.advance(Duration.ofSeconds(2));
        assertThrows(InvalidTokenException.class,
                () -> clockedService.validateAndExtractUser(token),
                "advancing the injected clock past the TTL must expire the token without sleeping");
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);
    }

    /** A clock the test moves explicitly, so expiry is deterministic. */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] repeatedBytes(int value) {
        byte[] data = new byte[32];
        java.util.Arrays.fill(data, (byte) value);
        return data;
    }

    private static Date future() {
        return new Date(System.currentTimeMillis() + 60_000);
    }

    private static String signedToken(String subject, String sessionType, String rawSecret, Date expiration) {
        var builder = Jwts.builder()
                .subject(subject)
                .issuedAt(new Date())
                .expiration(expiration);
        if (sessionType != null) {
            builder.claim(TokenService.SESSION_TYPE_CLAIM, sessionType);
        }
        return builder.signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(rawSecret))).compact();
    }
}
