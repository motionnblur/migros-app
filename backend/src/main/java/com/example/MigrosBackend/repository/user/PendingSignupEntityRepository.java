package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.user.PendingSignupEntity;
import com.example.MigrosBackend.entity.user.PendingTokenPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PendingSignupEntityRepository extends JpaRepository<PendingSignupEntity, String> {

    /**
     * Replaces the previous pending token for the same mailbox <em>and the same
     * purpose</em>. Scoping by purpose keeps an unused signup token from
     * silently invalidating an outstanding password reset (and vice versa):
     * the two flows are independent, each token is single-use on its own path.
     */
    @Transactional
    @Modifying
    void deleteByUserMailAndTokenPurpose(String userMail, PendingTokenPurpose tokenPurpose);

    /**
     * Consumes a token in one statement, bound to the caller's transaction.
     *
     * <p>The matching row is deleted, and the affected-row count is what makes
     * redemption single-use: when two callers race on the same token, only the
     * one whose DELETE removes the row gets {@code 1}; the other gets {@code 0}
     * and is reported as "token not found". A read-then-delete on separate
     * statements would let both callers pass the read.
     *
     * @return the number of rows deleted, {@code 0} or {@code 1} in practice
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM PendingSignupEntity p WHERE p.token = :token AND p.tokenPurpose = :purpose")
    int deleteByTokenAndPurpose(@Param("token") String token,
                                @Param("purpose") PendingTokenPurpose purpose);
}
