package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.entity.payment.PaymentAttemptEntity;
import com.example.MigrosBackend.exception.user.StalePaymentLeaseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Pure lease mechanics for payment attempts: token acquisition, extension,
 * clearing, and constant-time ownership fencing. Extracted verbatim from
 * {@link PaymentAttemptService}; it owns no state transitions and never reads
 * provider state.
 */
@Component
class PaymentAttemptLeases {

    private final long leaseSeconds;
    private final Clock clock;

    PaymentAttemptLeases(@Value("${payment.attempt.lease-seconds:120}") long leaseSeconds, Clock clock) {
        this.leaseSeconds = leaseSeconds;
        this.clock = clock;
    }

    String acquireLease(PaymentAttemptEntity attempt, LocalDateTime now) {
        String owner = UUID.randomUUID().toString();
        attempt.setLeaseOwner(owner);
        attempt.setLeaseExpiresAt(now.plusSeconds(leaseSeconds));
        attempt.setUpdatedAt(now);
        return owner;
    }

    /**
     * Extends the expiry of the currently held lease without rotating the
     * token, so the worker that just recorded success keeps authority over the
     * finalization window.
     */
    void extendLease(PaymentAttemptEntity attempt, LocalDateTime now) {
        attempt.setLeaseExpiresAt(now.plusSeconds(leaseSeconds));
        attempt.setUpdatedAt(now);
    }

    /**
     * Fencing check run inside the row-locked transaction before any worker
     * state change. Ownership is by token equality only — an expired but
     * unreplaced token still commits — because replacement (not the wall
     * clock) is what revokes a worker. Comparison is constant-time so lease
     * tokens are not subject to timing probing.
     */
    void requireCurrentLease(PaymentAttemptEntity attempt, String leaseOwner) {
        if (leaseOwner == null || leaseOwner.isBlank()
                || attempt.getLeaseOwner() == null
                || !constantTimeEquals(attempt.getLeaseOwner(), leaseOwner)) {
            throw new StalePaymentLeaseException(
                    "Stale payment lease: the attempt is owned by another worker");
        }
    }

    boolean constantTimeEquals(String stored, String presented) {
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    void clearLease(PaymentAttemptEntity attempt) {
        attempt.setLeaseOwner(null);
        attempt.setLeaseExpiresAt(null);
        attempt.setUpdatedAt(LocalDateTime.now(clock));
    }
}
