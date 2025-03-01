package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.user.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserEntityRepository extends JpaRepository<UserEntity, Long>  {
    boolean existsByUserMail(String userMail);
    UserEntity findByUserMail(String userMail);
}
