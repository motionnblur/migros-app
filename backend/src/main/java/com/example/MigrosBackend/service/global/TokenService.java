package com.example.MigrosBackend.service.global;

import com.example.MigrosBackend.exception.shared.InvalidTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

@Service
public class TokenService {
    public static final String SESSION_TYPE_CLAIM = "session_type";
    public static final String USER_SESSION_TYPE = "USER";
    public static final String ADMIN_SESSION_TYPE = "ADMIN";

    private static final long TOKEN_TTL_MILLIS = 1000L * 60 * 3;

    private static final java.util.Set<String> DOCUMENTED_PLACEHOLDERS = java.util.Set.of(
            "replace-with-a-random-base64-user-secret",
            "replace-with-a-different-random-base64-admin-secret"
    );

    private final byte[] userSecretBytes;
    private final byte[] adminSecretBytes;
    private final SecretKey userSigningKey;
    private final SecretKey adminSigningKey;
    private final Clock clock;

    public TokenService(@Value("${jwt.user-secret:}") String userSecret,
                        @Value("${jwt.admin-secret:}") String adminSecret,
                        Clock clock) {
        this.userSecretBytes = requireSecret(userSecret, "jwt.user-secret");
        this.adminSecretBytes = requireSecret(adminSecret, "jwt.admin-secret");
        this.clock = clock;

        if (MessageDigest.isEqual(userSecretBytes, adminSecretBytes)) {
            throw new IllegalStateException("jwt.user-secret and jwt.admin-secret must be different");
        }

        this.userSigningKey = Keys.hmacShaKeyFor(userSecretBytes);
        this.adminSigningKey = Keys.hmacShaKeyFor(adminSecretBytes);
    }

    public String generateUserToken(String subject) {
        return generateToken(subject, USER_SESSION_TYPE, userSigningKey);
    }

    public String generateAdminToken(String subject) {
        return generateToken(subject, ADMIN_SESSION_TYPE, adminSigningKey);
    }

    public String validateAndExtractUser(String token) {
        return validateAndExtract(token, USER_SESSION_TYPE, userSigningKey);
    }

    public String validateAndExtractAdmin(String token) {
        return validateAndExtract(token, ADMIN_SESSION_TYPE, adminSigningKey);
    }

    public long getTokenTtlMillis() {
        return TOKEN_TTL_MILLIS;
    }

    private String generateToken(String subject, String sessionType, SecretKey signingKey) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(subject)
                .claim(SESSION_TYPE_CLAIM, sessionType)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(TOKEN_TTL_MILLIS)))
                .signWith(signingKey)
                .compact();
    }

    private String validateAndExtract(String token, String expectedSessionType, SecretKey signingKey) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String sessionType = claims.get(SESSION_TYPE_CLAIM, String.class);
            if (!expectedSessionType.equals(sessionType)) {
                throw new InvalidTokenException();
            }

            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                throw new InvalidTokenException();
            }

            return subject;
        } catch (InvalidTokenException ex) {
            throw ex;
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidTokenException();
        }
    }

    private static byte[] requireSecret(String secret, String propertyName) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(propertyName + " must be configured with a Base64 encoded secret");
        }

        String normalized = secret.trim();
        if (DOCUMENTED_PLACEHOLDERS.contains(normalized)) {
            throw new IllegalStateException(propertyName + " still contains a template placeholder value");
        }

        byte[] decoded = tryDecode(Base64.getDecoder(), normalized);
        if (decoded == null) {
            decoded = tryDecode(Base64.getUrlDecoder(), normalized);
        }
        if (decoded == null) {
            throw new IllegalStateException(propertyName + " must be valid standard Base64 or Base64URL");
        }

        if (decoded.length < 32) {
            throw new IllegalStateException(propertyName + " must decode to at least 32 bytes");
        }

        return decoded;
    }

    private static byte[] tryDecode(Base64.Decoder decoder, String value) {
        try {
            return decoder.decode(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
