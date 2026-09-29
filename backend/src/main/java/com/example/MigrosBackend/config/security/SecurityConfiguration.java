package com.example.MigrosBackend.config.security;

import com.example.MigrosBackend.filter.InternalApiKeyFilter;
import com.example.MigrosBackend.filter.JwtRequestFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
    private final List<String> allowedOriginPatterns;
    private final boolean csrfCookieSecure;
    private final String csrfCookieSameSite;

    public SecurityConfiguration(
            @Value("${app.allowed-origins:}") String allowedOriginsValue,
            @Value("${app.allowed-origin-patterns:}") String allowedOriginPatternsValue,
            @Value("${auth.cookie.secure:false}") boolean csrfCookieSecure,
            @Value("${auth.cookie.same-site:Lax}") String csrfCookieSameSite
    ) {
        List<String> parsedPatterns = parseCsvList(allowedOriginPatternsValue);
        if (parsedPatterns.isEmpty()) {
            parsedPatterns = parseCsvList(allowedOriginsValue);
        }
        this.allowedOriginPatterns = parsedPatterns;
        this.csrfCookieSecure = csrfCookieSecure;
        this.csrfCookieSameSite = csrfCookieSameSite;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Cookie-backed CSRF tokens for the cross-origin SPA. The cookie is not
     * HttpOnly so the token can also be read by same-origin tooling, but the
     * Angular client never reads it: it obtains the token from {@code GET /csrf}
     * because a host-only cookie is unreadable across origins. Secure and
     * SameSite follow the session-cookie configuration so the CSRF cookie stays
     * usable when the frontend and API are genuinely cross-site.
     */
    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie
                .path("/")
                .secure(csrfCookieSecure)
                .sameSite(csrfCookieSameSite));
        return repository;
    }

    /**
     * Reports CSRF-filter failures as a stable JSON code so the SPA can tell
     * them apart from ordinary authorization denials. Spring Security installs
     * this handler on both the CSRF filter and the exception translation filter,
     * so every non-CSRF denial keeps the standard behavior.
     */
    @Bean
    public AccessDeniedHandler csrfAccessDeniedHandler(ObjectMapper objectMapper) {
        return new CsrfAccessDeniedHandler(objectMapper);
    }

    @Bean
    public InternalApiKeyFilter internalApiKeyFilter(
            @Value("${support.internal.key:}") String internalKey,
            ObjectMapper objectMapper) {
        return new InternalApiKeyFilter(internalKey, objectMapper);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtRequestFilter jwtRequestFilter,
            InternalApiKeyFilter internalApiKeyFilter,
            AccessDeniedHandler csrfAccessDeniedHandler) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .exceptionHandling(exception -> exception.accessDeniedHandler(csrfAccessDeniedHandler))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository())
                        // The Stripe webhook authenticates by verified raw-body
                        // signature and the internal bridge by x-internal-key;
                        // neither is a browser cookie flow and both are blocked
                        // at Nginx. Every other state-changing endpoint keeps
                        // CSRF enforcement.
                        .ignoringRequestMatchers("/payment/webhook", "/internal/**"))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/csrf").permitAll()
                        .requestMatchers(SecurityPaths.ADMIN_LOGIN, SecurityPaths.ADMIN_LOGOUT).permitAll()
                        .requestMatchers(SecurityPaths.USER_LOGIN, SecurityPaths.USER_LOGOUT).permitAll()
                        .requestMatchers("/user/signup", "/user/signup/**").permitAll()
                        .requestMatchers("/user/verifyUserMail", "/user/resetPassword").permitAll()
                        .requestMatchers("/user/session").hasRole("USER")
                        .requestMatchers("/admin/session").hasRole("ADMIN")
                        .requestMatchers("/admin/panel/**").hasRole("ADMIN")
                        .requestMatchers("/admin/supply/**").hasRole("ADMIN")
                        .requestMatchers("/payment/webhook").permitAll()
                        .requestMatchers("/payment/**").hasRole("USER")
                        .requestMatchers("/user/supply/addProductToUserCart").hasRole("USER")
                        .requestMatchers("/user/supply/removeProductFromUserCart").hasRole("USER")
                        .requestMatchers("/user/supply/updateProductCountInUserCart").hasRole("USER")
                        .requestMatchers("/user/supply/getAllOrderIds").hasRole("USER")
                        .requestMatchers("/user/supply/cancelOrder").hasRole("USER")
                        .requestMatchers("/user/supply/getOrderStatusByOrderId").hasRole("USER")
                        .requestMatchers("/user/supply/getUserOrders").hasRole("USER")
                        .requestMatchers("/user/supply/getUserOrderGroups").hasRole("USER")
                        .requestMatchers("/user/supply/getProductData").hasRole("USER")
                        .requestMatchers("/user/profile/**").hasRole("USER")
                        .requestMatchers("/user/support/**").hasRole("USER")
                        // Anonymous catalog reads. These are explicit so the
                        // deny-by-default catch-all below cannot make them public
                        // or private by accident.
                        .requestMatchers(
                                "/user/supply/getAllCategoryNames",
                                "/user/supply/getProductsFromCategory",
                                "/user/supply/getProductsFromSubcategory",
                                "/user/supply/getProductCountsFromSubcategory",
                                "/user/supply/getProductCountsFromCategory",
                                "/user/supply/getProductImageNames",
                                "/user/supply/getProductImage",
                                "/user/supply/getSubCategories",
                                "/user/supply/getProductDataWithProductId",
                                "/user/supply/getProductDescription"
                        ).permitAll()
                        // WebSocket handshakes: authentication happens in the
                        // handshake interceptor (cookie-validated), not here.
                        .requestMatchers("/ws/support", "/admin/ws/support").permitAll()
                        // Internal bridge: the InternalApiKeyFilter enforces the
                        // x-internal-key header before this chain authorizes.
                        .requestMatchers("/internal/**").permitAll()
                        // Health probes are public for the hosting platform. Every
                        // other Actuator endpoint is denied outright rather than
                        // inheriting the deny-by-default catch-all below.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        // Fail closed: any route not explicitly permitted above is
                        // denied, so a new endpoint is never public by omission.
                        .anyRequest().denyAll()
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(internalApiKeyFilter, AuthorizationFilter.class)
                .addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(allowedOriginPatterns);
        configuration.setAllowedMethods(Arrays.asList("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private List<String> parseCsvList(String value) {
        return Arrays.stream((value == null ? "" : value).split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .collect(Collectors.toList());
    }
}

