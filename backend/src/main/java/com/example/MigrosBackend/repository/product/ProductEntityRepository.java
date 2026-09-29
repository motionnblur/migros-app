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

import java.util.List;
import java.util.Optional;

public interface ProductEntityRepository extends JpaRepository<ProductEntity, Long> {
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

    Page<ProductEntity> findByProductCountGreaterThan(int productCount, Pageable pageable);

    int countByProductCountGreaterThan(int productCount);

    /**
     * Counts in-stock products per subcategory for one category in the database,
     * rather than loading the whole product list to group it in memory. The
     * filters mirror the previous in-memory stream: stock greater than zero and
     * a present, non-empty subcategory name.
     */
    @Query("""
            SELECT new com.example.MigrosBackend.repository.product.SubcategoryCount(
                       p.subcategoryName, COUNT(p))
            FROM ProductEntity p
            WHERE p.categoryEntity.id = :categoryId
              AND p.productCount > 0
              AND p.subcategoryName IS NOT NULL
              AND p.subcategoryName <> ''
            GROUP BY p.subcategoryName
            ORDER BY p.subcategoryName ASC
            """)
    List<SubcategoryCount> countProductsBySubcategory(@Param("categoryId") Long categoryId);
}
