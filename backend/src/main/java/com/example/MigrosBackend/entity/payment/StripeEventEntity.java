package com.example.MigrosBackend.entity.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Durable deduplication record for processed Stripe webhook events. The event id
 * is the primary key, so a repeated delivery violates the primary-key
 * constraint and is skipped exactly once.
 */
@Entity
@Table(name = "stripe_event_entity")
@Getter
@Setter
@NoArgsConstructor
public class StripeEventEntity {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false, length = 255)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;
}
