package com.example.MigrosBackend.entity.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "support_messages")
@Getter
@Setter
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SupportMessageEntity other = (SupportMessageEntity) o;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        if (id == null) {
            return System.identityHashCode(this);
        }
        return Objects.hash(getClass(), id);
    }

    @Override
    public String toString() {
        return "SupportMessageEntity{" +
                "id=" + id +
                ", userMail='" + userMail + '\'' +
                ", sender='" + sender + '\'' +
                ", externalMessageId='" + externalMessageId + '\'' +
                ", createdAt=" + createdAt +
                ", editedAt=" + editedAt +
                '}';
    }
}
