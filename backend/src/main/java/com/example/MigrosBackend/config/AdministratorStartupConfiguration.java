package com.example.MigrosBackend.config;

import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AdministratorStartupConfiguration {

    private static final String DEFAULT_ADMIN_NAME = "admin";
    private static final String DEFAULT_ADMIN_PASSWORD = "admin";

    @Bean
    public CommandLineRunner localDefaultAdministratorInitializer(AdminEntityRepository adminEntityRepository,
                                                                  PasswordEncoder passwordEncoder,
                                                                  AdminStartupProfilePolicy profilePolicy) {
        return args -> {
            if (!profilePolicy.isLocalDevelopment()) {
                return;
            }
            if (!adminEntityRepository.existsByAdminName(DEFAULT_ADMIN_NAME)) {
                AdminEntity adminEntity = new AdminEntity();
                adminEntity.setAdminName(DEFAULT_ADMIN_NAME);
                adminEntity.setAdminPassword(passwordEncoder.encode(DEFAULT_ADMIN_PASSWORD));
                adminEntityRepository.save(adminEntity);
            }
        };
    }

    @Bean
    public SmartInitializingSingleton nonLocalDefaultAdministratorGuard(AdminEntityRepository adminEntityRepository,
                                                                        PasswordEncoder passwordEncoder,
                                                                        AdminStartupProfilePolicy profilePolicy) {
        return () -> {
            if (profilePolicy.isLocalDevelopment()) {
                return;
            }
            AdminEntity adminEntity = adminEntityRepository.findByAdminName(DEFAULT_ADMIN_NAME);
            if (adminEntity != null && passwordEncoder.matches(DEFAULT_ADMIN_PASSWORD, adminEntity.getAdminPassword())) {
                throw new IllegalStateException(
                        "Refusing to start: the 'admin' account still uses the default development password. "
                                + "Rotate or remove the 'admin' row in admin_entity before starting a non-local environment.");
            }
        };
    }
}
