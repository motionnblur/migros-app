package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.user.SupportMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SupportMessageEntityRepository extends JpaRepository<SupportMessageEntity, Long> {
    List<SupportMessageEntity> findByUserMailOrderByCreatedAtAscIdAsc(String userMail);

    @Query("SELECT DISTINCT s.userMail FROM SupportMessageEntity s ORDER BY s.userMail ASC")
    List<String> findDistinctUserMails();

    void deleteByUserMail(String userMail);
}
