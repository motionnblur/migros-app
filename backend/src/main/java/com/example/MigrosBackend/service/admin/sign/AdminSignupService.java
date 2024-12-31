package com.example.MigrosBackend.service.admin.sign;

import com.example.MigrosBackend.dto.admin.sign.AdminSignDto;
import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.repository.AdminEntityRepository;
import com.example.MigrosBackend.service.global.EncryptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AdminSignupService {
    private final AdminEntityRepository adminEntityRepository;
    private final EncryptService encryptService;

    @Autowired
    public AdminSignupService(AdminEntityRepository adminEntityRepository, EncryptService encryptService) {
        this.adminEntityRepository = adminEntityRepository;
        this.encryptService = encryptService;
    }

    public void signup(AdminSignDto adminSignDto) {
        AdminEntity adminEntityToCreate = new AdminEntity();
        adminEntityToCreate.setAdminName(adminSignDto.getAdminName());
        adminEntityToCreate.setAdminPassword(encryptService.getEncryptedPassword(adminSignDto.getAdminPassword()));

        if(adminEntityRepository.existsByAdminName(adminSignDto.getAdminName()))
            throw new RuntimeException("Admin with that name: "+ adminSignDto.getAdminName()+" already exists.");

        adminEntityRepository.save(adminEntityToCreate);
    }
    public void login(AdminSignDto adminSignDto) {
        AdminEntity adminEntity = adminEntityRepository.findByAdminName(adminSignDto.getAdminName());
        if (adminEntity == null) throw new RuntimeException("Admin with that name: " + adminSignDto.getAdminName() + " could not be found.");

        if(!encryptService.checkIfPasswordMatches(adminSignDto.getAdminPassword(), adminEntity.getAdminPassword()))
            throw new RuntimeException("Wrong password.");
    }
}
