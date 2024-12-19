package com.example.MigrosBackend.repository;

import com.example.MigrosBackend.entity.ItemImageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ItemImageEntityRepository extends JpaRepository<ItemImageEntity, Long> {
    List<ItemImageEntity> findByItemEntityId(Long id);
}
