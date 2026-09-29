package com.example.MigrosBackend.config.security;

import com.example.MigrosBackend.config.GlobalExceptionHandler;
import com.example.MigrosBackend.controller.admin.sign.AdminSignController;
import com.example.MigrosBackend.controller.user.profile.UserProfileController;
import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.exception.user.MailSendingFailedException;
import com.example.MigrosBackend.filter.JwtRequestFilter;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.service.admin.sign.AdminSignupService;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.user.profile.UserProfileService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        UserProfileController.class,
        AdminSignController.class,
        SecurityHeadersProbeController.class
})
@AutoConfigureMockMvc
@Import({SecurityConfiguration.class, JwtRequestFilter.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "support.internal.key=test-internal-key")
class SecurityHeadersIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserProfileService userProfileService;

    @MockBean
    private AdminSignupService adminSignupService;

    @MockBean
    private AuthCookieService authCookieService;

    @MockBean
    private AuthTokenResolver authTokenResolver;

    @MockBean
    private TokenService tokenService;

    @MockBean
    private AdminEntityRepository adminEntityRepository;

    @Test
    void authenticatedUserResponse_hasSecurityHeaders() throws Exception {
        String token = "user.jwt.token";
        when(tokenService.validateAndExtractUser(token)).thenReturn("user@example.com");
        when(authTokenResolver.requireToken(token)).thenReturn(token);
        when(userProfileService.getUserProfileTable(token)).thenReturn(new UserProfileTableDto());

        ResultActions result = mockMvc.perform(get("/user/profile/getUserProfileTable")
                .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, token)));

        result.andExpect(status().isOk());
        assertSecurityHeaders(result);
    }

    @Test
    void authenticatedAdminResponse_hasSecurityHeaders() throws Exception {
        String token = "admin.jwt.token";
        when(tokenService.validateAndExtractAdmin(token)).thenReturn("admin");
        when(adminEntityRepository.findByAdminName("admin")).thenReturn(new AdminEntity());
        when(authTokenResolver.requireToken(token)).thenReturn(token);

        ResultActions result = mockMvc.perform(get("/admin/session")
                .cookie(new Cookie(AuthCookies.ADMIN_SESSION_COOKIE_NAME, token)));

        result.andExpect(status().isOk());
        assertSecurityHeaders(result);
    }

    @Test
    void unauthenticatedDeniedResponse_hasSecurityHeaders() throws Exception {
        ResultActions result = mockMvc.perform(get("/user/profile/getUserProfileTable"));

        result.andExpect(status().isForbidden());
        assertSecurityHeaders(result);
    }

    @Test
    void handledExceptionResponse_hasSecurityHeaders() throws Exception {
        String token = "user.jwt.token";
        when(tokenService.validateAndExtractUser(token)).thenReturn("user@example.com");
        when(authTokenResolver.requireToken(token)).thenReturn(token);
        when(userProfileService.getUserProfileTable(token)).thenThrow(new MailSendingFailedException());

        ResultActions result = mockMvc.perform(get("/user/profile/getUserProfileTable")
                .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, token)));

        result.andExpect(status().isInternalServerError());
        assertSecurityHeaders(result);
    }

    @Test
    void sendErrorResponse_hasSecurityHeaders() throws Exception {
        ResultActions result = mockMvc.perform(get("/user/profile/security-headers/send-error")
                .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "user.jwt.token")));

        result.andExpect(status().isInternalServerError());
        assertSecurityHeaders(result);
    }

    @Test
    void contentLengthSetViaIntHeaderStillWritesSecurityHeaders() throws Exception {
        ResultActions result = mockMvc.perform(get("/user/profile/security-headers/content-length")
                .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "user.jwt.token")));

        result.andExpect(status().isOk());
        assertSecurityHeaders(result);
    }

    private void assertSecurityHeaders(ResultActions result) throws Exception {
        result.andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Cache-Control", containsString("max-age=0")))
                .andExpect(header().string("Cache-Control", containsString("must-revalidate")))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("Expires", "0"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }
}
