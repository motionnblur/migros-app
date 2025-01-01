package com.example.MigrosBackend.repository;

import com.example.MigrosBackend.entity.product.ProductDescriptionEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductDescriptionEntityRepository extends JpaRepository<ProductDescriptionEntity, Long>  {
    ProductDescriptionEntity findByProductEntityId(Long id);
}
