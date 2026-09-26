package com.example.MigrosBackend.entity.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Durable inbox record for a signature-verified Stripe webhook event.
 *
 * <p>The event id is the primary key so concurrent duplicate deliveries
 * converge on a single row, but unlike the pre-V4 deduplication record a row
 * merely existing never means the event is done: only
 * {@link StripeEventStatus#PROCESSED} (via {@code processed_at}) marks
 * completion. A crash before or during processing leaves the row
 * {@code RECEIVED}, {@code PROCESSING} with an expired lease, or
 * {@code FAILED} with a due retry, all of which redelivery or the scheduled
 * recovery job can reclaim.
 *
 * <p>The stored {@code payload} is the exact raw body that passed signature
 * verification, kept so the event can be replayed without waiting for Stripe
 * to redeliver. It is sensitive operational data: never log it, never expose
 * it via the API, and let retention cleanup delete it after the documented
 * retention period. The webhook secret and request signatures are never
 * stored here.
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

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private StripeEventStatus status = StripeEventStatus.RECEIVED;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_error", length = 64)
    private String lastError;

    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;
}
