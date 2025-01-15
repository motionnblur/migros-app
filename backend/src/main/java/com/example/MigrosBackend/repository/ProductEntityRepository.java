package com.example.MigrosBackend.repository;

import com.example.MigrosBackend.entity.product.ProductEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductEntityRepository extends JpaRepository<ProductEntity, Long> {
    ProductEntity findByProductName(String productName);
    Page<ProductEntity> findByCategoryEntityId(Long categoryId, Pageable pageable);
    Page<ProductEntity> findByAdminEntityId(Long adminId, Pageable pageable);
    Page<ProductEntity> findBySubcategoryName(String subcategoryName, Pageable pageable);
    int countByCategoryEntityId(Long categoryId);
}
