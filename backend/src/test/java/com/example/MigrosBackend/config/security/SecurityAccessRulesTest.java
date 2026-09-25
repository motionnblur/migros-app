package com.example.MigrosBackend.config.security;

import com.example.MigrosBackend.controller.admin.sign.AdminSignController;
import com.example.MigrosBackend.controller.user.profile.UserProfileController;
import com.example.MigrosBackend.controller.user.sign.UserSignController;
import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.filter.JwtRequestFilter;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.service.admin.sign.AdminSignupService;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.user.profile.UserProfileService;
import com.example.MigrosBackend.service.user.sign.UserSignupService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {UserProfileController.class, AdminSignController.class, UserSignController.class})
@AutoConfigureMockMvc
@Import({SecurityConfiguration.class, JwtRequestFilter.class})
class SecurityAccessRulesTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserProfileService userProfileService;

    @MockBean
    private AdminSignupService adminSignupService;

    @MockBean
    private UserSignupService userSignupService;

    @MockBean
    private AuthCookieService authCookieService;

    @MockBean
    private AuthTokenResolver authTokenResolver;

    @MockBean
    private TokenService tokenService;

    @MockBean
    private AdminEntityRepository adminEntityRepository;

    @Test
    void userCookie_GrantsAccessToProfileEndpoint() throws Exception {
        String token = "user.jwt.token";
        when(tokenService.validateAndExtractUser(token)).thenReturn("user@example.com");
        when(authTokenResolver.requireToken(token)).thenReturn(token);
        when(userProfileService.getUserProfileTable(token)).thenReturn(new UserProfileTableDto());

        mockMvc.perform(get("/user/profile/getUserProfileTable")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, token)))
                .andExpect(status().isOk());
    }

    @Test
    void noCredentials_AreForbiddenOnProfileEndpoint() throws Exception {
        mockMvc.perform(get("/user/profile/getUserProfileTable"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminAuthority_IsForbiddenOnUserEndpoint() throws Exception {
        mockMvc.perform(get("/user/profile/getUserProfileTable"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userAuthority_IsForbiddenOnAdminSessionEndpoint() throws Exception {
        mockMvc.perform(get("/admin/session"))
                .andExpect(status().isForbidden());
    }

    @Test
    void validAdminCookie_GrantsAccessToAdminSessionEndpoint() throws Exception {
        String token = "admin.jwt.token";
        when(tokenService.validateAndExtractAdmin(token)).thenReturn("admin");
        when(adminEntityRepository.findByAdminName("admin")).thenReturn(new AdminEntity());
        when(authTokenResolver.requireToken(token)).thenReturn(token);

        mockMvc.perform(get("/admin/session")
                        .cookie(new Cookie(AuthCookies.ADMIN_SESSION_COOKIE_NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminName").value("admin"));
    }

    @Test
    void userCookie_GrantsAccessToUserSessionEndpoint() throws Exception {
        String token = "user.jwt.token";
        when(tokenService.validateAndExtractUser(token)).thenReturn("user@example.com");
        when(authTokenResolver.requireToken(token)).thenReturn(token);

        mockMvc.perform(get("/user/session")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userMail").value("user@example.com"));
    }

    @Test
    void noCredentials_AreForbiddenOnUserSessionEndpoint() throws Exception {
        mockMvc.perform(get("/user/session"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminAuthority_IsForbiddenOnUserSessionEndpoint() throws Exception {
        mockMvc.perform(get("/user/session"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCookie_CannotAccessUserSessionEndpoint() throws Exception {
        mockMvc.perform(get("/user/session")
                        .cookie(new Cookie(AuthCookies.ADMIN_SESSION_COOKIE_NAME, "admin.jwt.token")))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginEndpoint_IsPubliclyAccessibleWithoutSession() throws Exception {
        ResponseCookie cookie = ResponseCookie.from(AuthCookies.USER_SESSION_COOKIE_NAME, "token").path("/").build();
        when(userSignupService.login(any())).thenReturn("token");
        when(authCookieService.createUserSessionCookie("token")).thenReturn(cookie);

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }
}
