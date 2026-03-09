package com.example.MigrosBackend.filter;

import com.example.MigrosBackend.config.security.AuthCookies;
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

@Component
public class JwtRequestFilter extends OncePerRequestFilter {
    private final TokenService tokenService;

    public JwtRequestFilter(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String servletPath = request.getServletPath();
        return servletPath.equals("/admin/login") || servletPath.equals("/user/login");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        boolean isAdminPath = request.getServletPath().startsWith("/admin");
        String jwt = resolveToken(request, isAdminPath);
        String username = null;

        if (jwt != null) {
            try {
                username = tokenService.extractUsername(jwt);
            } catch (Exception ex) {
                filterChain.doFilter(request, response);
                return;
            }
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            if (tokenService.validateToken(jwt, username)) {
                List<SimpleGrantedAuthority> authorities;

                if (isAdminPath) {
                    authorities = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
                } else if ("admin".equalsIgnoreCase(username)) {
                    authorities = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
                } else {
                    authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
                }

                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(username, null, authorities);

                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request, boolean isAdminPath) {
        if (isAdminPath) {
            return getCookieToken(request, AuthCookies.ADMIN_SESSION_COOKIE_NAME);
        }

        final String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        return getCookieToken(request, AuthCookies.USER_SESSION_COOKIE_NAME);
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
}
