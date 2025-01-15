package com.example.MigrosBackend.repository;

import com.example.MigrosBackend.entity.category.SubCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SubCategoryEntityRepository extends JpaRepository<SubCategoryEntity, Long>  {
}
