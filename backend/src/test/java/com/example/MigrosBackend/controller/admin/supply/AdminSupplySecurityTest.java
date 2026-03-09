package com.example.MigrosBackend.controller.admin.supply;

import com.example.MigrosBackend.config.security.AuthCookies;
import com.example.MigrosBackend.config.security.SecurityConfiguration;
import com.example.MigrosBackend.filter.JwtRequestFilter;
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

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Test
    void userBearerToken_CannotAccessAdminSupplyEndpoint() throws Exception {
        String userToken = "user.bearer.token";
        when(tokenService.extractUsername(userToken)).thenReturn("customer@example.com");
        when(tokenService.validateToken(userToken, "customer@example.com")).thenReturn(true);

        mockMvc.perform(get("/admin/supply/addCategory")
                        .param("categoryName", "Electronics")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        verify(adminSupplyService, never()).addCategory(anyString());
    }

    @Test
    void adminCookie_CanAccessAdminSupplyEndpoint() throws Exception {
        String adminToken = "admin.cookie.token";
        when(tokenService.extractUsername(adminToken)).thenReturn("manager@example.com");
        when(tokenService.validateToken(adminToken, "manager@example.com")).thenReturn(true);

        mockMvc.perform(get("/admin/supply/addCategory")
                        .param("categoryName", "Electronics")
                        .cookie(new Cookie(AuthCookies.ADMIN_SESSION_COOKIE_NAME, adminToken)))
                .andExpect(status().isOk());

        verify(adminSupplyService).addCategory("Electronics");
    }

    @Test
    void userCookie_CannotAuthenticateAdminPath() throws Exception {
        mockMvc.perform(get("/admin/supply/addCategory")
                        .param("categoryName", "Electronics")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "user.cookie.token")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(tokenService);
        verify(adminSupplyService, never()).addCategory(anyString());
    }
}
