package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.user.UserEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserEntityRepository extends JpaRepository<UserEntity, Long>  {
    boolean existsByUserMail(String userMail);

    UserEntity findByUserMail(String userMail);

    List<UserEntity> findByBannedTrueOrderByUserMailAsc();

    @Query("""
            SELECT u
            FROM UserEntity u
            WHERE (
                :query IS NULL OR :query = '' OR
                LOWER(u.userMail) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(u.userName, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(COALESCE(u.userLastName, '')) LIKE LOWER(CONCAT('%', :query, '%'))
            )
            ORDER BY u.userMail ASC
            """)
    List<UserEntity> searchForSupportCustomers(@Param("query") String query, Pageable pageable);
}
