package com.example.MigrosBackend.config;

import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Collections;

@Configuration
public class UserDetailsServiceConfig {

    private final AdminEntityRepository adminEntityRepository;

    public UserDetailsServiceConfig(AdminEntityRepository adminEntityRepository) {
        this.adminEntityRepository = adminEntityRepository;
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            AdminEntity admin = adminEntityRepository.findByAdminName(username);
            if (admin == null) {
                throw new UsernameNotFoundException("Admin not found with username: " + username);
            }
            // You can add proper roles/authorities here if your entity has them
            return new User(admin.getAdminName(), admin.getAdminPassword(), Collections.emptyList());
        };
    }
}