package com.example.MigrosBackend.repository;

import com.example.MigrosBackend.entity.AdminEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AdminEntityRepository extends JpaRepository<AdminEntity, Long> {
    boolean existsByAdminName(String adminName);
}
