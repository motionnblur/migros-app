package com.example.MigrosBackend.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationProfileDefaultsTest {

    @Test
    void applicationPropertiesDoesNotDefaultToLocalProfile() throws IOException {
        Properties properties = new Properties();
        try (InputStream inputStream = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(inputStream);
        }

        assertThat(properties).doesNotContainKey("spring.profiles.default");
    }
}
