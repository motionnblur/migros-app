package com.example.MigrosBackend.repository;

import com.example.MigrosBackend.entity.category.CategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CategoryEntityRepository extends JpaRepository<CategoryEntity, Long> {
    CategoryEntity findByCategoryId(int categoryId);
    CategoryEntity findByCategoryName(String categoryName);
    boolean existsByCategoryName(String categoryName);
}
