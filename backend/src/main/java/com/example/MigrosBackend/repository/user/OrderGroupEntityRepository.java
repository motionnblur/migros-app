package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderGroupEntityRepository extends JpaRepository<OrderGroupEntity, Long> {
    List<OrderGroupEntity> findByUserId(Long userId);
    Optional<OrderGroupEntity> findByIdAndUserId(Long id, Long userId);
}
