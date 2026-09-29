package com.example.MigrosBackend.repository.product;

import com.example.MigrosBackend.entity.product.ProductImageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductImageEntityRepository extends JpaRepository<ProductImageEntity, Long> {
    List<ProductImageEntity> findByProductEntityId(Long id);
}
