package com.example.MigrosBackend.filter;

import com.example.MigrosBackend.config.security.AuthCookies;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtRequestFilterTest {
    @Mock
    private TokenService tokenService;

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

        boolean result = jwtRequestFilter.shouldNotFilter(request);

        assertTrue(result, "Filter should be skipped for /admin/login");

        request.setServletPath("/user/login");
        assertTrue(jwtRequestFilter.shouldNotFilter(request), "Filter should be skipped for /user/login");
    }

    @Test
    void doFilterInternal_SetsAdminAuthentication_WhenUsernameIsAdmin() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String token = "mock.jwt.token";
        request.addHeader("Authorization", "Bearer " + token);

        when(tokenService.extractUsername(token)).thenReturn("admin");
        when(tokenService.validateToken(token, "admin")).thenReturn(true);

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("admin", SecurityContextHolder.getContext().getAuthentication().getName());
        assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                .contains(new SimpleGrantedAuthority("ROLE_ADMIN")));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_SetsUserAuthentication_WhenUsernameIsRegularUser() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String token = "user.jwt.token";
        request.addHeader("Authorization", "Bearer " + token);

        when(tokenService.extractUsername(token)).thenReturn("customer@email.com");
        when(tokenService.validateToken(token, "customer@email.com")).thenReturn(true);

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                .contains(new SimpleGrantedAuthority("ROLE_USER")));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_SetsAuthentication_WhenTokenProvidedByCookie() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String token = "cookie.jwt.token";
        request.setCookies(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, token));

        when(tokenService.extractUsername(token)).thenReturn("user@example.com");
        when(tokenService.validateToken(token, "user@example.com")).thenReturn(true);

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                .contains(new SimpleGrantedAuthority("ROLE_USER")));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_SkipsAuthentication_WhenHeaderIsMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(tokenService);
    }

    @Test
    void doFilterInternal_SkipsAuthentication_WhenTokenIsInvalid() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String token = "invalid.token";
        request.addHeader("Authorization", "Bearer " + token);

        when(tokenService.extractUsername(token)).thenReturn("user");
        when(tokenService.validateToken(token, "user")).thenReturn(false);

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_UsesAdminCookie_ForAdminPath_AndAssignsAdminRole() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String token = "admin.cookie.token";
        request.setServletPath("/admin/panel");
        request.setCookies(new Cookie(AuthCookies.ADMIN_SESSION_COOKIE_NAME, token));

        when(tokenService.extractUsername(token)).thenReturn("manager@example.com");
        when(tokenService.validateToken(token, "manager@example.com")).thenReturn(true);

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                .contains(new SimpleGrantedAuthority("ROLE_ADMIN")));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_DoesNotUseAuthorizationHeader_OnAdminPath() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setServletPath("/admin/panel");
        request.addHeader("Authorization", "Bearer user.token");

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(tokenService);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_DoesNotUseUserCookie_OnAdminPath() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setServletPath("/admin/panel");
        request.setCookies(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "user.token"));

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(tokenService);
        verify(filterChain).doFilter(request, response);
    }
}
