package com.example.MigrosBackend.repository;

import com.example.MigrosBackend.entity.ProductImageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductImageEntityRepository extends JpaRepository<ProductImageEntity, Long> {
    List<ProductImageEntity> findByProductEntityId(Long id);
}
