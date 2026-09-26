package com.example.MigrosBackend.controller.admin.supply;

import com.example.MigrosBackend.config.security.AuthCookies;
import com.example.MigrosBackend.config.security.SecurityConfiguration;
import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.exception.shared.InvalidTokenException;
import com.example.MigrosBackend.filter.JwtRequestFilter;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.service.admin.supply.AdminSupplyService;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.user.supply.UserSupplyService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminSupplyController.class)
@AutoConfigureMockMvc
@Import({SecurityConfiguration.class, JwtRequestFilter.class})
class AdminSupplySecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminSupplyService adminSupplyService;

    @MockBean
    private UserSupplyService userSupplyService;

    @MockBean
    private TokenService tokenService;

    @MockBean
    private AdminEntityRepository adminEntityRepository;

    @Test
    void userToken_InAdminCookie_CannotAccessAdminSupplyEndpoint() throws Exception {
        String userToken = "user.jwt.token";
        when(tokenService.validateAndExtractAdmin(userToken)).thenThrow(new InvalidTokenException());

        mockMvc.perform(post("/admin/supply/addCategory").with(csrf())
                        .param("categoryName", "Electronics")
                        .cookie(new Cookie(AuthCookies.ADMIN_SESSION_COOKIE_NAME, userToken)))
                .andExpect(status().isForbidden());

        verify(adminEntityRepository, never()).findByAdminName(any());
        verify(adminSupplyService, never()).addCategory(anyString());
    }

    @Test
    void adminToken_WithoutAdminRecord_CannotAccessAdminSupplyEndpoint() throws Exception {
        String adminToken = "ghost.admin.token";
        when(tokenService.validateAndExtractAdmin(adminToken)).thenReturn("ghost@example.com");
        when(adminEntityRepository.findByAdminName("ghost@example.com")).thenReturn(null);

        mockMvc.perform(post("/admin/supply/addCategory").with(csrf())
                        .param("categoryName", "Electronics")
                        .cookie(new Cookie(AuthCookies.ADMIN_SESSION_COOKIE_NAME, adminToken)))
                .andExpect(status().isForbidden());

        verify(adminSupplyService, never()).addCategory(anyString());
    }

    @Test
    void validAdminToken_CanAccessAdminSupplyEndpoint() throws Exception {
        String adminToken = "admin.jwt.token";
        when(tokenService.validateAndExtractAdmin(adminToken)).thenReturn("manager@example.com");
        when(adminEntityRepository.findByAdminName("manager@example.com")).thenReturn(new AdminEntity());

        mockMvc.perform(post("/admin/supply/addCategory").with(csrf())
                        .param("categoryName", "Electronics")
                        .cookie(new Cookie(AuthCookies.ADMIN_SESSION_COOKIE_NAME, adminToken)))
                .andExpect(status().isOk());

        verify(adminSupplyService).addCategory("Electronics");
    }

    @Test
    void bearerHeader_CannotAuthenticateAdminPath() throws Exception {
        mockMvc.perform(post("/admin/supply/addCategory").with(csrf())
                        .param("categoryName", "Electronics")
                        .header("Authorization", "Bearer some.jwt.token"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(tokenService);
        verify(adminSupplyService, never()).addCategory(anyString());
    }

    @Test
    void userCookie_CannotAuthenticateAdminPath() throws Exception {
        mockMvc.perform(post("/admin/supply/addCategory").with(csrf())
                        .param("categoryName", "Electronics")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "user.cookie.token")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(tokenService);
        verify(adminSupplyService, never()).addCategory(anyString());
    }
}
