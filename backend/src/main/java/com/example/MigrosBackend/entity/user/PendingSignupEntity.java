package com.example.MigrosBackend.entity.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PendingSignupEntity {
    @Id
    @Column(nullable = false, length = 64)
    private String token;

    @Column(nullable = false)
    private String userMail;

    @Column(nullable = false)
    private String userPassword;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /**
     * Binds the token to the single flow allowed to redeem it. A token stored
     * without a purpose (legacy row) is never redeemable: it is treated as a
     * non-match so a migrated token cannot be replayed against the wrong
     * endpoint.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "token_purpose", nullable = false, length = 32)
    private PendingTokenPurpose tokenPurpose = PendingTokenPurpose.SIGNUP;
}
