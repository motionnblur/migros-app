package com.example.MigrosBackend.service.global;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Base64;

@Service
public class TokenService {
    private static final long TOKEN_TTL_MILLIS = 1000L * 60 * 3;
    private static final String DEFAULT_LOCAL_SECRET = "local-dev-secret-change-me";

    @Value("${jwt.secret:" + DEFAULT_LOCAL_SECRET + "}")
    private String secretKey = DEFAULT_LOCAL_SECRET;

    public String generateToken(String username) {
        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + TOKEN_TTL_MILLIS)) // 3 min
                .signWith(SignatureAlgorithm.HS256, resolveSigningKeyBytes())
                .compact();
    }

    public long getTokenTtlMillis() {
        return TOKEN_TTL_MILLIS;
    }

    // Validate token
    public boolean validateToken(String token, String username) {
        String extractedUsername = extractUsername(token);
        return (extractedUsername.equals(username) && !isTokenExpired(token));
    }

    // Extract username from token
    public String extractUsername(String token) {
        return Jwts.parser()
                .setSigningKey(resolveSigningKeyBytes()).build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }

    // Check if token is expired
    public boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    // Extract expiration date from token
    private Date extractExpiration(String token) {
        return Jwts.parser()
                .setSigningKey(resolveSigningKeyBytes()).build()
                .parseClaimsJws(token)
                .getBody()
                .getExpiration();
    }

    private byte[] resolveSigningKeyBytes() {
        String normalizedSecret = secretKey == null ? "" : secretKey.trim();
        if (normalizedSecret.isEmpty()) {
            normalizedSecret = DEFAULT_LOCAL_SECRET;
        }

        byte[] base64Decoded = tryDecodeBase64(normalizedSecret);
        if (base64Decoded != null && base64Decoded.length >= 32) {
            return base64Decoded;
        }

        byte[] base64UrlDecoded = tryDecodeBase64Url(normalizedSecret);
        if (base64UrlDecoded != null && base64UrlDecoded.length >= 32) {
            return base64UrlDecoded;
        }

        byte[] utf8Secret = normalizedSecret.getBytes(StandardCharsets.UTF_8);
        if (utf8Secret.length >= 32) {
            return utf8Secret;
        }

        try {
            return MessageDigest.getInstance("SHA-256").digest(utf8Secret);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm is not available", ex);
        }
    }

    private byte[] tryDecodeBase64(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private byte[] tryDecodeBase64Url(String value) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
