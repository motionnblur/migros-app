package com.example.MigrosBackend.repository.admin;

import com.example.MigrosBackend.entity.admin.AdminEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminEntityRepository extends JpaRepository<AdminEntity, Long> {
    boolean existsByAdminName(String adminName);
    AdminEntity findByAdminName(String adminName);
}
