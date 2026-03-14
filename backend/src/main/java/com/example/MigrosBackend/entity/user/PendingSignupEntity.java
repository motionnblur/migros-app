package com.example.MigrosBackend.entity.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
}
