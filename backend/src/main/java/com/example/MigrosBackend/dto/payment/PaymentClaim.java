package com.example.MigrosBackend.dto.payment;

import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;

import java.util.UUID;

public record PaymentClaim(
        PaymentClaimDecision decision,
        UUID attemptId,
        UUID checkoutId,
        String idempotencyKey,
        long amountMinor,
        String currency,
        String leaseOwner,
        String chargeId,
        PaymentAttemptStatus state
) {
}
