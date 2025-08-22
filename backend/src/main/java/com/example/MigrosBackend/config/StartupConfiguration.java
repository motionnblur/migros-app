package com.example.MigrosBackend.config;

import com.example.MigrosBackend.config.security.SecurityConfiguration;
import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.service.admin.sign.AdminSignupService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StartupConfiguration {
    private final SecurityConfiguration securityConfiguration;
    private final AdminSignupService adminSignupService;

    public StartupConfiguration(SecurityConfiguration securityConfiguration, AdminSignupService adminSignupService) {
        this.securityConfiguration = securityConfiguration;
        this.adminSignupService = adminSignupService;
    }
    @Bean
    public CommandLineRunner run(CategoryEntityRepository categoryEntityRepository, AdminEntityRepository adminEntityRepository) {
        return (args) -> {
            if(!adminEntityRepository.existsByAdminName("admin")){
                AdminEntity adminEntity = new AdminEntity();
                adminEntity.setAdminName("admin");
                adminEntity.setAdminPassword(securityConfiguration.passwordEncoder().encode("admin"));
                adminEntityRepository.save(adminEntity);
            }

            String[] categoryNames = {
                    "Yılbaşı",
                    "Meyve, Sebze",
                    "Süt, Kahvaltılık",
                    "Temel Gıda",
                    "Meze, Hazır yemek, Donut",
                    "İçecek",
                    "Dondurma",
                    "Atistirmalik",
                    "Fırın, Pastane",
                    "Deterjan, Temizlik",
                    "Kağıt, Islak mendil",
                    "Kişisel Bakım,Kozmetik, Sağlık",
                    "Bebek",
                    "Ev, Yaşam",
                    "Kitap, Kırtasiye, Oyuncak",
                    "Çiçek",
                    "Pet Shop",
                    "Elektronik"
            };

            int idCounter = 1;
            for (String categoryName : categoryNames) {
                if (!categoryEntityRepository.existsByCategoryName(categoryName)) {
                    CategoryEntity categoryEntity = new CategoryEntity();
                    categoryEntity.setCategoryId(idCounter++);
                    categoryEntity.setCategoryName(categoryName);

                    categoryEntityRepository.save(categoryEntity);
                }
            }
        };
    }
}
