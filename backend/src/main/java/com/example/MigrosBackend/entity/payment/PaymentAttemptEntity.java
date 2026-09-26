package com.example.MigrosBackend.entity.payment;

import com.example.MigrosBackend.entity.checkout.CheckoutEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Durable record of a single attempt to charge one immutable checkout. The
 * unique checkout relationship enforces the one-charge-per-checkout design at
 * the database level even when application checks race.
 *
 * The Stripe source token is intentionally never stored here.
 */
@Entity
@Table(
        name = "payment_attempt_entity",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_payment_attempt_checkout", columnNames = "checkout_id"),
                @UniqueConstraint(name = "uq_payment_attempt_idempotency", columnNames = "idempotency_key"),
                @UniqueConstraint(name = "uq_payment_attempt_charge", columnNames = "stripe_charge_id")
        },
        indexes = {
                @Index(name = "idx_payment_attempt_status_updated", columnList = "status, updated_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class PaymentAttemptEntity {

    @Id
    @Column(name = "attempt_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "checkout_id", nullable = false, updatable = false, unique = true)
    private CheckoutEntity checkout;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private Long amountMinor;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentAttemptStatus status;

    @Column(name = "stripe_charge_id", length = 255, unique = true)
    private String stripeChargeId;

    @Column(name = "provider_status", length = 64)
    private String providerStatus;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "refund_id", length = 255)
    private String refundId;

    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public UUID getCheckoutId() {
        return checkout == null ? null : checkout.getId();
    }
}
