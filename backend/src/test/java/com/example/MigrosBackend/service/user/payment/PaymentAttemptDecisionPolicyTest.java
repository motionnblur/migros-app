package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.PaymentClaimDecision;
import com.example.MigrosBackend.entity.payment.PaymentAttemptEntity;
import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentAttemptDecisionPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 29, 12, 0);

    @Test
    void forStatusMapsDurableStatesToClaimDecisions() {
        PaymentAttemptEntity attempt = new PaymentAttemptEntity();

        assertEquals(PaymentClaimDecision.FINALIZED,
                PaymentAttemptDecisionPolicy.forStatus(PaymentAttemptStatus.ORDER_FINALIZED, attempt, NOW));
        assertEquals(PaymentClaimDecision.FINALIZED,
                PaymentAttemptDecisionPolicy.forStatus(PaymentAttemptStatus.REFUNDED, attempt, NOW));
        assertEquals(PaymentClaimDecision.FINALIZE,
                PaymentAttemptDecisionPolicy.forStatus(PaymentAttemptStatus.CHARGE_SUCCEEDED, attempt, NOW));
        assertEquals(PaymentClaimDecision.PROCEED,
                PaymentAttemptDecisionPolicy.forStatus(PaymentAttemptStatus.CREATED, attempt, NOW));
        assertEquals(PaymentClaimDecision.TERMINAL,
                PaymentAttemptDecisionPolicy.forStatus(PaymentAttemptStatus.FAILED_FINAL, attempt, NOW));
        assertEquals(PaymentClaimDecision.TERMINAL,
                PaymentAttemptDecisionPolicy.forStatus(PaymentAttemptStatus.REFUND_PENDING, attempt, NOW));
        assertEquals(PaymentClaimDecision.TERMINAL,
                PaymentAttemptDecisionPolicy.forStatus(PaymentAttemptStatus.MANUAL_REVIEW, attempt, NOW));
    }

    @Test
    void processingDecisionDependsOnAnUnexpiredOwnedLease() {
        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setLeaseOwner("worker");
        attempt.setLeaseExpiresAt(NOW.plusNanos(1));

        assertTrue(PaymentAttemptDecisionPolicy.hasValidLease(attempt, NOW));
        assertEquals(PaymentClaimDecision.PENDING,
                PaymentAttemptDecisionPolicy.forStatus(PaymentAttemptStatus.PROCESSING, attempt, NOW));

        attempt.setLeaseExpiresAt(NOW);
        assertFalse(PaymentAttemptDecisionPolicy.hasValidLease(attempt, NOW));
        assertEquals(PaymentClaimDecision.PROCEED,
                PaymentAttemptDecisionPolicy.forStatus(PaymentAttemptStatus.PROCESSING, attempt, NOW));

        attempt.setLeaseExpiresAt(NOW.plusSeconds(1));
        attempt.setLeaseOwner(null);
        assertFalse(PaymentAttemptDecisionPolicy.hasValidLease(attempt, NOW));
    }
}
