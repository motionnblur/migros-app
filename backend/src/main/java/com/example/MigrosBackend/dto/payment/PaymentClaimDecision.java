package com.example.MigrosBackend.dto.payment;

/**
 * Outcome of claiming a payment attempt in a short transaction. PROCEED means
 * the caller owns the lease and must call the provider; FINALIZE means the
 * provider charge is already durably recorded and only local order finalization
 * remains.
 */
public enum PaymentClaimDecision {
    PROCEED,
    FINALIZE,
    PENDING,
    FINALIZED,
    TERMINAL
}
