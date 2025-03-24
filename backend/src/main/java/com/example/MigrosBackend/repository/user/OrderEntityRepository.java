package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.user.OrderEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderEntityRepository extends JpaRepository<OrderEntity, Long> {
    OrderEntity findByAdminId(Long adminId);
    Page<OrderEntity> findByAdminId(Long adminId, Pageable pageable);
}
