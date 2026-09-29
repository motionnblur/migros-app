package com.example.MigrosBackend.service.user.sign;

import com.example.MigrosBackend.entity.user.PendingSignupEntity;
import com.example.MigrosBackend.entity.user.PendingTokenPurpose;
import com.example.MigrosBackend.repository.user.PendingSignupEntityRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * Durable storage for one-time signup/password-reset tokens.
 *
 * <p>The database is the only store. There is deliberately no in-memory fallback
 * and no background expiry scheduler: a fallback would let a request report
 * success ("check your mail") for a token that only exists in one process's heap
 * and is unreachable from every other instance and from any restart, turning a
 * database outage into a silently broken signup. Storage failures propagate to
 * the caller so the HTTP response reflects the outage instead.
 *
 * <p>Expiry is enforced on read against {@code expiresAt}; an expired token is
 * removed when it is presented, so no scheduler is needed to keep the table
 * correct.
 */
final class PendingSignupStorage {

    private final PendingSignupEntityRepository repository;
    private final TransactionTemplate requiresNewTransaction;

    PendingSignupStorage(PendingSignupEntityRepository repository,
                         PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Persists a pending token, replacing any earlier token with the same
     * purpose for the same mailbox. The write must be committed before the
     * confirmation mail is sent, otherwise the mail can reference a token that
     * no longer exists.
     */
    void store(PendingSignupEntity pendingSignup) {
        PendingTokenPurpose purpose = requirePurpose(pendingSignup);
        repository.deleteByUserMailAndTokenPurpose(pendingSignup.getUserMail(), purpose);
        repository.save(pendingSignup);
    }

    /**
     * @return the stored token when it exists, has not expired, and was issued
     *         for exactly {@code purpose}; {@code null} in every other case,
     *         including a token issued for a different purpose. Returning
     *         {@code null} for a purpose mismatch is intentional: a caller must
     *         not be able to distinguish "wrong purpose" from "no such token".
     */
    PendingSignupEntity findActiveToken(String token, PendingTokenPurpose purpose) {
        if (token == null || token.isBlank() || purpose == null) {
            return null;
        }

        PendingSignupEntity stored = repository.findById(token).orElse(null);
        if (stored == null) {
            return null;
        }
        if (stored.getTokenPurpose() != purpose) {
            return null;
        }
        if (stored.getExpiresAt() == null || !stored.getExpiresAt().isAfter(LocalDateTime.now())) {
            deleteExpired(token);
            return null;
        }
        return stored;
    }

    /**
     * Removes a redeemed token as part of the caller's transaction, so the token
     * disappears exactly when the work it authorized becomes durable and a
     * failure in that work leaves the token usable.
     */
    void delete(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        repository.deleteById(token);
    }

    /**
     * Removes an already-expired token in its own transaction.
     *
     * <p>Deleting it from the caller's transaction would be pointless: the
     * caller rejects the token by throwing, which rolls that transaction back
     * and would resurrect the row. Expired tokens are then never reclaimed and
     * the table grows without bound. A separate commit makes the cleanup
     * durable, and it is safe to do so here precisely because the token can no
     * longer be redeemed for anything.
     */
    private void deleteExpired(String token) {
        requiresNewTransaction.executeWithoutResult(status -> repository.deleteById(token));
    }

    private static PendingTokenPurpose requirePurpose(PendingSignupEntity pendingSignup) {
        PendingTokenPurpose purpose = pendingSignup.getTokenPurpose();
        if (purpose == null) {
            throw new IllegalStateException("A pending token must record its purpose before it is stored");
        }
        return purpose;
    }
}
