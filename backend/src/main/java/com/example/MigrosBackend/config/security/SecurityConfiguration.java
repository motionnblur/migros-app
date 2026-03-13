package com.example.MigrosBackend.config.security;

import com.example.MigrosBackend.filter.JwtRequestFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
    private final List<String> allowedOrigins;

    public SecurityConfiguration(@Value("${app.allowed-origins}") String allowedOriginsValue) {
        this.allowedOrigins = Arrays.stream(allowedOriginsValue.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toList());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtRequestFilter jwtRequestFilter) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/admin/login", "/admin/logout").permitAll()
                        .requestMatchers("/user/login", "/user/logout").permitAll()
                        .requestMatchers("/admin/panel/**").hasRole("ADMIN")
                        .requestMatchers("/admin/supply/**").hasRole("ADMIN")
                        .requestMatchers("/payment/**").authenticated()
                        .requestMatchers("/user/supply/addProductToUserCart").authenticated()
                        .requestMatchers("/user/supply/removeProductFromUserCart").authenticated()
                        .requestMatchers("/user/supply/updateProductCountInUserCart").authenticated()
                        .requestMatchers("/user/supply/getAllOrderIds").authenticated()
                        .requestMatchers("/user/supply/cancelOrder").authenticated()
                        .requestMatchers("/user/supply/getOrderStatusByOrderId").authenticated()
                        .requestMatchers("/user/supply/getUserOrders").authenticated()
                        .requestMatchers("/user/supply/getUserOrderGroups").authenticated()
                        .requestMatchers("/user/supply/getProductData").authenticated()
                        .requestMatchers("/user/profile/**").authenticated()
                        .requestMatchers("/user/support/**").authenticated()
                        .anyRequest().permitAll()
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

