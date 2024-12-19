package com.example.MigrosBackend.repository;

import com.example.MigrosBackend.entity.CategoryEntity;
import com.example.MigrosBackend.entity.ItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CategoryEntityRepository extends JpaRepository<CategoryEntity, Long> {
    CategoryEntity findByCategoryName(String itemName);
    @Query("SELECT x FROM ItemEntity x WHERE x.id BETWEEN :id_start AND :id_end")
    List<ItemEntity> getAllItemIDsFromCategory(@Param("categoryName") String categoryName, @Param("id_start") Long id1, @Param("id_end") Long id2);
}
