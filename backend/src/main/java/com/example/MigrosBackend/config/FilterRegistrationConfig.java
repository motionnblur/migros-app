package com.example.MigrosBackend.config;

import com.example.MigrosBackend.filter.JwtRequestFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link JwtRequestFilter} is a servlet {@code @Component}, so Spring Boot would
 * register it in the servlet container as well as adding it to the security
 * chain. A second, container-level registration runs it for every request
 * outside the chain and would execute the filter twice. Disable the automatic
 * registration so {@code SecurityConfiguration}'s {@code addFilterBefore} stays
 * the filter's only registration.
 */
@Configuration
public class FilterRegistrationConfig {

    @Bean
    public FilterRegistrationBean<JwtRequestFilter> jwtRequestFilterRegistration(JwtRequestFilter jwtRequestFilter) {
        FilterRegistrationBean<JwtRequestFilter> registration = new FilterRegistrationBean<>(jwtRequestFilter);
        registration.setEnabled(false);
        return registration;
    }
}
