package com.example.MigrosBackend.repository;

import com.example.MigrosBackend.entity.ItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ItemEntityRepository extends JpaRepository<ItemEntity, Long> {
    ItemEntity findByItemName(String itemName);
}
