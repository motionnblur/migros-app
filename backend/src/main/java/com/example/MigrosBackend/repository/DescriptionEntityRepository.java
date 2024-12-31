package com.example.MigrosBackend.repository;

import com.example.MigrosBackend.entity.product.DescriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DescriptionEntityRepository extends JpaRepository<DescriptionEntity, Long>  {
    //
}
