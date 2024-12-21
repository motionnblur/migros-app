package com.example.MigrosBackend.service.admin;

import com.example.MigrosBackend.dto.AdminSignupDto;
import com.example.MigrosBackend.entity.AdminEntity;
import com.example.MigrosBackend.repository.AdminEntityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AdminSignupService {
    private final AdminEntityRepository adminEntityRepository;

    @Autowired
    public AdminSignupService(AdminEntityRepository adminEntityRepository) {
        this.adminEntityRepository = adminEntityRepository;
    }

    public void signup(AdminSignupDto adminSignupDto) {
        AdminEntity adminEntityToCreate = new AdminEntity();
        adminEntityToCreate.setAdminName(adminSignupDto.getAdminName());
        adminEntityToCreate.setAdminPassword(adminSignupDto.getAdminPassword());

        boolean b = adminEntityRepository.existsByAdminName(adminSignupDto.getAdminName());
        if(b) throw new RuntimeException("Admin with that name: "+adminSignupDto.getAdminName()+" already exists.");

        adminEntityRepository.save(adminEntityToCreate);
    }
}
