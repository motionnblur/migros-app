package com.example.MigrosBackend.filter;

import com.example.MigrosBackend.config.security.AuthCookies;
import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.exception.shared.InvalidTokenException;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtRequestFilterTest {
    @Mock
    private TokenService tokenService;

    @Mock
    private AdminEntityRepository adminEntityRepository;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private JwtRequestFilter jwtRequestFilter;

    @BeforeEach
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldNotFilter_ReturnsTrue_ForLoginEndpoints() throws ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/admin/login");

        assertTrue(jwtRequestFilter.shouldNotFilter(request), "Filter should be skipped for /admin/login");

        request.setServletPath("/user/login");
        assertTrue(jwtRequestFilter.shouldNotFilter(request), "Filter should be skipped for /user/login");
    }

    @Test
    void shouldNotFilter_UsesRequestUri_WhenServletPathIsBlank() throws ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/login");

        assertTrue(jwtRequestFilter.shouldNotFilter(request));
    }

    @Test
    void adminCookie_GrantsRoleAdmin_WhenAdminExists() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String token = "admin.jwt.token";
        request.setServletPath("/admin/panel");
        request.setCookies(new Cookie(AuthCookies.ADMIN_SESSION_COOKIE_NAME, token));

        when(tokenService.validateAndExtractAdmin(token)).thenReturn("manager@example.com");
        when(adminEntityRepository.findByAdminName("manager@example.com")).thenReturn(new AdminEntity());

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertEquals("manager@example.com", authentication.getName());
        assertTrue(authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN")));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void adminCookie_IsRejected_WhenAdminSubjectDoesNotExist() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String token = "admin.jwt.token";
        request.setServletPath("/admin/panel");
        request.setCookies(new Cookie(AuthCookies.ADMIN_SESSION_COOKIE_NAME, token));

        when(tokenService.validateAndExtractAdmin(token)).thenReturn("ghost@example.com");
        when(adminEntityRepository.findByAdminName("ghost@example.com")).thenReturn(null);

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void userToken_InAdminCookie_IsRejectedOnAdminPath() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String token = "user.jwt.token";
        request.setServletPath("/admin/panel");
        request.setCookies(new Cookie(AuthCookies.ADMIN_SESSION_COOKIE_NAME, token));

        when(tokenService.validateAndExtractAdmin(token)).thenThrow(new InvalidTokenException());

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(adminEntityRepository, never()).findByAdminName(any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void userCookie_IsIgnored_OnAdminPath() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setServletPath("/admin/panel");
        request.setCookies(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "user.token"));

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(tokenService);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void adminCookie_IsIgnored_OnNonAdminPath() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setServletPath("/user/profile");
        request.setCookies(new Cookie(AuthCookies.ADMIN_SESSION_COOKIE_NAME, "admin.token"));

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(tokenService);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void userCookie_GrantsRoleUser() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String token = "user.jwt.token";
        request.setServletPath("/user/profile");
        request.setCookies(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, token));

        when(tokenService.validateAndExtractUser(token)).thenReturn("customer@email.com");

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertEquals("customer@email.com", authentication.getName());
        assertTrue(authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_USER")));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void userNamedAdmin_StillGetsRoleUser() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String token = "user.jwt.token";
        request.setServletPath("/user/profile");
        request.setCookies(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, token));

        when(tokenService.validateAndExtractUser(token)).thenReturn("admin");

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertTrue(authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_USER")));
        assertFalse(authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    void bearerHeader_IsIgnored() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setServletPath("/user/profile");
        request.addHeader("Authorization", "Bearer some.jwt.token");

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(tokenService);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void userCookie_IsRejected_WhenTokenIsInvalid() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String token = "invalid.token";
        request.setServletPath("/user/profile");
        request.setCookies(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, token));

        when(tokenService.validateAndExtractUser(token)).thenThrow(new InvalidTokenException());

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void skipsAuthentication_WhenNoCredentialsPresent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setServletPath("/user/profile");

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(tokenService);
        verify(filterChain).doFilter(request, response);
    }
}
