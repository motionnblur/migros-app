package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.PaymentClaimDecision;
import com.example.MigrosBackend.entity.payment.PaymentAttemptEntity;
import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;

import java.time.LocalDateTime;

/** Pure lease-validity and claim-decision policy for payment attempts. */
final class PaymentAttemptDecisionPolicy {

    private PaymentAttemptDecisionPolicy() {
    }

    static boolean hasValidLease(PaymentAttemptEntity attempt, LocalDateTime now) {
        return attempt.getLeaseOwner() != null
                && attempt.getLeaseExpiresAt() != null
                && attempt.getLeaseExpiresAt().isAfter(now);
    }

    static PaymentClaimDecision forStatus(PaymentAttemptStatus state,
                                          PaymentAttemptEntity attempt,
                                          LocalDateTime now) {
        return switch (state) {
            case ORDER_FINALIZED, REFUNDED -> PaymentClaimDecision.FINALIZED;
            case CHARGE_SUCCEEDED -> PaymentClaimDecision.FINALIZE;
            case PROCESSING -> hasValidLease(attempt, now)
                    ? PaymentClaimDecision.PENDING
                    : PaymentClaimDecision.PROCEED;
            case CREATED -> PaymentClaimDecision.PROCEED;
            default -> PaymentClaimDecision.TERMINAL;
        };
    }
}
