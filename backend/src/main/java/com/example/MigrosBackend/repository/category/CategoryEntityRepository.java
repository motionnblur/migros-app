package com.example.MigrosBackend.repository.category;

import com.example.MigrosBackend.entity.category.CategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryEntityRepository extends JpaRepository<CategoryEntity, Long> {
    CategoryEntity findByCategoryId(int categoryId);
    CategoryEntity findByCategoryName(String categoryName);
    boolean existsByCategoryName(String categoryName);
}
