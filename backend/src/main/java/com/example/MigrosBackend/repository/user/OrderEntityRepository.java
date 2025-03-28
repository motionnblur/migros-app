package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderEntityRepository extends JpaRepository<OrderEntity, Long> {
    Page<OrderEntity> findAll(Pageable pageable);
    UserEntity findByUserId(Long userId);
}
