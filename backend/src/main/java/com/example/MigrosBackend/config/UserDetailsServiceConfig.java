package com.example.MigrosBackend.config;

import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Configuration
public class UserDetailsServiceConfig {

    private final AdminEntityRepository adminEntityRepository;
    private final UserEntityRepository userEntityRepository;

    public UserDetailsServiceConfig(AdminEntityRepository adminEntityRepository, UserEntityRepository userEntityRepository) {
        this.adminEntityRepository = adminEntityRepository;
        this.userEntityRepository = userEntityRepository;
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            // First, try to find an admin
            AdminEntity admin = adminEntityRepository.findByAdminName(username);
            if (admin != null) {
                // Return admin user details with a specific role
                List<GrantedAuthority> authorities = Arrays.asList(new SimpleGrantedAuthority("ROLE_ADMIN"));
                return new User(admin.getAdminName(), admin.getAdminPassword(), authorities);
            }

            // If no admin is found, try to find a regular user
            UserEntity user = userEntityRepository.findByUserMail(username); // Assuming findByUsername method
            if (user != null) {
                // Return regular user details with a specific role
                List<GrantedAuthority> authorities = Arrays.asList(new SimpleGrantedAuthority("ROLE_USER"));
                return new User(user.getUserName(), user.getUserPassword(), authorities);
            }

            // If neither is found, throw an exception
            throw new UsernameNotFoundException("User not found with username: " + username);
        };
    }
}