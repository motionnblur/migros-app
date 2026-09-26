package com.example.MigrosBackend.repository.product;

import com.example.MigrosBackend.entity.product.ProductEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductEntityRepository extends JpaRepository<ProductEntity, Long> {
    ProductEntity findByProductName(String productName);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ProductEntity p WHERE p.id = :id")
    Optional<ProductEntity> findByIdForUpdate(@Param("id") Long id);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE ProductEntity p SET p.productCount = p.productCount + :quantity WHERE p.id = :id")
    int incrementStock(@Param("id") Long id, @Param("quantity") int quantity);

    Page<ProductEntity> findByCategoryEntityIdAndProductCountGreaterThan(Long categoryId, int productCount, Pageable pageable);

    Page<ProductEntity> findByAdminEntityId(Long adminId, Pageable pageable);

    Page<ProductEntity> findBySubcategoryNameAndProductCountGreaterThan(String subcategoryName, int productCount, Pageable pageable);

    int countByCategoryEntityIdAndProductCountGreaterThan(Long categoryId, int productCount);

    int countBySubcategoryNameAndProductCountGreaterThan(String subcategoryName, int productCount);
}
