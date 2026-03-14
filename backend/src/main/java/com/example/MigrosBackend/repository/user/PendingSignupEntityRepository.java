package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.user.PendingSignupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface PendingSignupEntityRepository extends JpaRepository<PendingSignupEntity, String> {
    @Transactional
    @Modifying
    void deleteByUserMail(String userMail);
}
