package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.user.PendingSignupEntity;
import com.example.MigrosBackend.entity.user.PendingTokenPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
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
}
