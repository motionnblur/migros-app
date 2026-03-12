package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.user.SupportMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SupportMessageEntityRepository extends JpaRepository<SupportMessageEntity, Long> {
    List<SupportMessageEntity> findByUserMailOrderByCreatedAtAscIdAsc(String userMail);

    Optional<SupportMessageEntity> findByUserMailAndExternalMessageId(String userMail, String externalMessageId);

    Optional<SupportMessageEntity> findByIdAndUserMail(Long id, String userMail);

    @Query("SELECT DISTINCT s.userMail FROM SupportMessageEntity s ORDER BY s.userMail ASC")
    List<String> findDistinctUserMails();

    @Query("SELECT DISTINCT s.userMail FROM SupportMessageEntity s WHERE s.userMail IN :userMails")
    List<String> findDistinctUserMailsIn(@Param("userMails") List<String> userMails);

    boolean existsByUserMail(String userMail);

    void deleteByUserMail(String userMail);
}
