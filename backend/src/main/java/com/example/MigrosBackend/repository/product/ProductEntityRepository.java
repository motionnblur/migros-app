package com.example.MigrosBackend.repository.product;

import com.example.MigrosBackend.entity.product.ProductEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductEntityRepository extends JpaRepository<ProductEntity, Long> {
    ProductEntity findByProductName(String productName);

    Page<ProductEntity> findByCategoryEntityIdAndProductCountGreaterThan(Long categoryId, int productCount, Pageable pageable);

    Page<ProductEntity> findByAdminEntityId(Long adminId, Pageable pageable);

    Page<ProductEntity> findBySubcategoryNameAndProductCountGreaterThan(String subcategoryName, int productCount, Pageable pageable);

    int countByCategoryEntityIdAndProductCountGreaterThan(Long categoryId, int productCount);

    int countBySubcategoryNameAndProductCountGreaterThan(String subcategoryName, int productCount);
}
