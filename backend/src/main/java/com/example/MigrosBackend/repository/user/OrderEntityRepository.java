package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.user.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderEntityRepository extends JpaRepository<OrderEntity, Long> {
    List<OrderEntity> findByOrderGroup_Id(Long orderGroupId);
    List<OrderEntity> findByOrderGroupIsNull();
    List<OrderEntity> findByUserIdAndOrderGroupIsNull(Long userId);
    Optional<OrderEntity> findByIdAndUserId(Long id, Long userId);
}
