package com.example.MigrosBackend.repository.product;

import com.example.MigrosBackend.entity.product.ProductDescriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductDescriptionEntityRepository extends JpaRepository<ProductDescriptionEntity, Long>  {
    List<ProductDescriptionEntity> findByProductEntityId(Long id);
}
