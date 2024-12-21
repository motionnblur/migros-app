package com.example.MigrosBackend.service.admin.sign;

import com.example.MigrosBackend.config.SecurityConfiguration;
import com.example.MigrosBackend.dto.admin.sign.AdminSignDto;
import com.example.MigrosBackend.entity.AdminEntity;
import com.example.MigrosBackend.repository.AdminEntityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AdminSignupService {
    private final AdminEntityRepository adminEntityRepository;
    private final SecurityConfiguration securityConfiguration;

    @Autowired
    public AdminSignupService(AdminEntityRepository adminEntityRepository, SecurityConfiguration securityConfiguration) {
        this.adminEntityRepository = adminEntityRepository;
        this.securityConfiguration = securityConfiguration;
    }

    public void signup(AdminSignDto adminSignDto) {
        AdminEntity adminEntityToCreate = new AdminEntity();
        adminEntityToCreate.setAdminName(adminSignDto.getAdminName());
        adminEntityToCreate.setAdminPassword(securityConfiguration.passwordEncoder().encode(adminSignDto.getAdminPassword()));

        boolean b = adminEntityRepository.existsByAdminName(adminSignDto.getAdminName());
        if(b) throw new RuntimeException("Admin with that name: "+ adminSignDto.getAdminName()+" already exists.");

        adminEntityRepository.save(adminEntityToCreate);
    }

    public void login(AdminSignDto adminSignDto) {
        boolean b = adminEntityRepository.existsByAdminName(adminSignDto.getAdminName());
        if(!b) throw new RuntimeException("Admin with that name: "+ adminSignDto.getAdminName()+" could not be found.");

        AdminEntity adminEntity = adminEntityRepository.findByAdminName(adminSignDto.getAdminName());
        if(!securityConfiguration.passwordEncoder().matches(adminSignDto.getAdminPassword(), adminEntity.getAdminPassword()))
            throw new RuntimeException("Wrong password.");
    }
}
