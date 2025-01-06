package com.example.MigrosBackend.repository;

import com.example.MigrosBackend.entity.product.ProductDescriptionEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductDescriptionEntityRepository extends JpaRepository<ProductDescriptionEntity, Long>  {
    List<ProductDescriptionEntity> findByProductEntityId(Long id);
}
