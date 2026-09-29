package com.example.MigrosBackend.entity.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class PendingSignupEntity {
    @Id
    @Column(nullable = false, length = 64)
    @ToString.Exclude
    private String token;

    @Column(nullable = false)
    private String userMail;

    @Column(nullable = false)
    @ToString.Exclude
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PendingSignupEntity other = (PendingSignupEntity) o;
        return token != null && token.equals(other.token);
    }

    @Override
    public int hashCode() {
        if (token == null) {
            return System.identityHashCode(this);
        }
        return Objects.hash(getClass(), token);
    }
}
