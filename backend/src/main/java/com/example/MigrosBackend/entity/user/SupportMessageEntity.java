package com.example.MigrosBackend.entity.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "support_messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupportMessageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userMail;

    @Column(nullable = false)
    private String sender;

    @Column(nullable = false, length = 2000)
    private String message;

    @Column(unique = true)
    private String externalMessageId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime editedAt;

    public SupportMessageEntity(Long id, String userMail, String sender, String message, LocalDateTime createdAt) {
        this.id = id;
        this.userMail = userMail;
        this.sender = sender;
        this.message = message;
        this.createdAt = createdAt;
    }

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
