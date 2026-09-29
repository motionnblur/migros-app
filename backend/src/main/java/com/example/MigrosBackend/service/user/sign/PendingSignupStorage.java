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
     * purpose for the same mailbox.
     *
     * <p>The write runs in its own transaction and is <em>committed</em> before
     * this method returns. That ordering is the whole point: the issuing method
     * sends the confirmation mail only after the token is durable, so a
     * delivered link can never reference a token that a later rollback - or a
     * crash - removed. Sharing the caller's transaction instead would send the
     * mail first and leave a window in which the link is live and the token is
     * not.
     *
     * <p>Because the commit already happened, an issuance whose mail cannot be
     * delivered must revoke the token explicitly with {@link
     * #deleteCommitted(String)}.
     */
    void store(PendingSignupEntity pendingSignup) {
        PendingTokenPurpose purpose = requirePurpose(pendingSignup);
        requiresNewTransaction.executeWithoutResult(status -> {
            repository.deleteByUserMailAndTokenPurpose(pendingSignup.getUserMail(), purpose);
            repository.save(pendingSignup);
        });
    }

    /**
     * Removes an already-committed token in its own transaction.
     *
     * <p>Used to revoke an issuance whose mail could not be delivered: the token
     * was committed before the send precisely so the link could not outrun the
     * database, and it has to be removed explicitly for the same reason. The
     * caller owns the failure being reported, so a throwing cleanup is left to
     * that caller to decide how to surface.
     */
    void deleteCommitted(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        requiresNewTransaction.executeWithoutResult(status -> repository.deleteById(token));
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
     * Consumes a token atomically, <em>inside the caller's transaction</em>.
     *
     * <p>Unlike {@link #store(PendingSignupEntity)}, this method deliberately
     * does not open a transaction of its own: the redemption must commit or
     * roll back together with the work it authorizes (inserting the user or
     * setting the password). Sharing the caller's transaction is what makes a
     * failed insert leave the token usable again.
     *
     * <p>Redemption is a conditional DELETE, not a read followed by a later
     * delete. The row is read first (a non-locking read, so the expiry check
     * cannot take a lock it does not need), then deleted with the token and
     * purpose in the WHERE clause. When two callers race, the database lets
     * exactly one DELETE remove the row; the other sees {@code 0} affected rows
     * and is reported as "token not found". A read-then-delete on separate
     * statements would let both callers pass the read and both create the user.
     *
     * @return the entity that was deleted, or {@code null} when the token is
     *         absent, expired, issued for a different purpose, or was already
     *         redeemed by a concurrent caller
     */
    PendingSignupEntity redeem(String token, PendingTokenPurpose purpose) {
        if (token == null || token.isBlank() || purpose == null) {
            return null;
        }

        PendingSignupEntity stored = repository.findById(token).orElse(null);
        if (stored == null || stored.getTokenPurpose() != purpose) {
            return null;
        }
        if (stored.getExpiresAt() == null || !stored.getExpiresAt().isAfter(LocalDateTime.now())) {
            deleteExpired(token);
            return null;
        }

        int deleted = repository.deleteByTokenAndPurpose(token, purpose);
        if (deleted == 0) {
            return null;
        }
        return stored;
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
