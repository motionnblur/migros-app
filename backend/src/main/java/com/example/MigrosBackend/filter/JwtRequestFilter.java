package com.example.MigrosBackend.filter;

import com.example.MigrosBackend.config.security.AuthCookies;
import com.example.MigrosBackend.exception.shared.InvalidTokenException;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
public class JwtRequestFilter extends OncePerRequestFilter {
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_USER = "ROLE_USER";

    private final TokenService tokenService;
    private final AdminEntityRepository adminEntityRepository;

    public JwtRequestFilter(TokenService tokenService, AdminEntityRepository adminEntityRepository) {
        this.tokenService = tokenService;
        this.adminEntityRepository = adminEntityRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String requestPath = resolveRequestPath(request);
        return requestPath.equals("/admin/login") || requestPath.equals("/user/login");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        resolveAuthentication(request).ifPresent(authentication -> {
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        });

        filterChain.doFilter(request, response);
    }

    private Optional<UsernamePasswordAuthenticationToken> resolveAuthentication(HttpServletRequest request) {
        if (resolveRequestPath(request).startsWith("/admin")) {
            return authenticateAdmin(request);
        }

        return authenticateUser(request);
    }

    private Optional<UsernamePasswordAuthenticationToken> authenticateAdmin(HttpServletRequest request) {
        String token = getCookieToken(request, AuthCookies.ADMIN_SESSION_COOKIE_NAME);
        if (token == null) {
            return Optional.empty();
        }

        try {
            String adminName = tokenService.validateAndExtractAdmin(token);
            if (adminEntityRepository.findByAdminName(adminName) == null) {
                return Optional.empty();
            }
            return Optional.of(buildAuthentication(adminName, ROLE_ADMIN));
        } catch (InvalidTokenException ex) {
            return Optional.empty();
        }
    }

    private Optional<UsernamePasswordAuthenticationToken> authenticateUser(HttpServletRequest request) {
        String token = getCookieToken(request, AuthCookies.USER_SESSION_COOKIE_NAME);
        if (token == null) {
            return Optional.empty();
        }

        try {
            String userMail = tokenService.validateAndExtractUser(token);
            return Optional.of(buildAuthentication(userMail, ROLE_USER));
        } catch (InvalidTokenException ex) {
            return Optional.empty();
        }
    }

    private UsernamePasswordAuthenticationToken buildAuthentication(String subject, String role) {
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(role));
        return new UsernamePasswordAuthenticationToken(subject, null, authorities);
    }

    private String getCookieToken(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }

        return null;
    }

    private String resolveRequestPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isBlank()) {
            return servletPath;
        }

        String requestUri = request.getRequestURI();
        return requestUri != null ? requestUri : "";
    }
}
