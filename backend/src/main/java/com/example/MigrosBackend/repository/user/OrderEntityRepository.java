package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.user.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderEntityRepository extends JpaRepository<OrderEntity, Long> {
    OrderEntity findByAdminId(Long adminId);
}
