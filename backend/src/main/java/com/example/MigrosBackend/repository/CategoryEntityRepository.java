package com.example.MigrosBackend.repository;

import com.example.MigrosBackend.entity.CategoryEntity;
import com.example.MigrosBackend.entity.ItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CategoryEntityRepository extends JpaRepository<CategoryEntity, Long> {
    CategoryEntity findByCategoryName(String itemName);
}
