package com.example.MigrosBackend.config.security;

import com.example.MigrosBackend.config.GlobalExceptionHandler;
import com.example.MigrosBackend.controller.admin.sign.AdminSignController;
import com.example.MigrosBackend.controller.internal.InternalSupportController;
import com.example.MigrosBackend.controller.security.CsrfController;
import com.example.MigrosBackend.controller.user.payment.StripeWebhookController;
import com.example.MigrosBackend.controller.user.sign.UserSignController;
import com.example.MigrosBackend.controller.user.supply.UserSupplyController;
import com.example.MigrosBackend.filter.JwtRequestFilter;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.service.admin.sign.AdminSignupService;
import com.example.MigrosBackend.service.global.LogService;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.support.SupportCustomerDirectoryService;
import com.example.MigrosBackend.service.support.SupportModerationService;
import com.example.MigrosBackend.service.user.payment.PaymentWebhookService;
import com.example.MigrosBackend.service.user.payment.StripeWebhookVerifier;
import com.example.MigrosBackend.service.user.sign.UserSignupService;
import com.example.MigrosBackend.service.user.supply.UserCartService;
import com.example.MigrosBackend.service.user.supply.UserSupplyService;
import com.stripe.model.Event;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fail-closed route policy coverage: every legitimately public route must be
 * reachable anonymously, an unmapped route must be denied, and the internal
 * bridge must reject a missing or wrong {@code x-internal-key}.
 */
@WebMvcTest(controllers = {
        CsrfController.class,
        UserSignController.class,
        AdminSignController.class,
        StripeWebhookController.class,
        UserSupplyController.class,
        InternalSupportController.class
})
@AutoConfigureMockMvc
@Import({SecurityConfiguration.class, JwtRequestFilter.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "support.internal.key=test-internal-key",
        "app.allowed-origins=",
        "app.allowed-origin-patterns="
})
class SecurityPublicRoutesTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserSignupService userSignupService;

    @MockBean
    private AdminSignupService adminSignupService;

    @MockBean
    private UserSupplyService userSupplyService;

    @MockBean
    private UserCartService userCartService;

    @MockBean
    private SupportCustomerDirectoryService supportCustomerDirectoryService;

    @MockBean
    private SupportModerationService supportModerationService;

    @MockBean
    private StripeWebhookVerifier stripeWebhookVerifier;

    @MockBean
    private PaymentWebhookService paymentWebhookService;

    @MockBean
    private AuthCookieService authCookieService;

    @MockBean
    private AuthTokenResolver authTokenResolver;

    @MockBean
    private TokenService tokenService;

    @MockBean
    private AdminEntityRepository adminEntityRepository;

    @MockBean
    private LogService logService;

    @Test
    void unmappedPathIsForbidden() throws Exception {
        mockMvc.perform(get("/this/route/does/not/exist"))
                .andExpect(status().isForbidden());
    }

    @Test
    void csrfBootstrapIsPublic() throws Exception {
        mockMvc.perform(get("/csrf"))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousCatalogAndSignupGetRoutesAreReachable() throws Exception {
        String[] publicGetRoutes = {
                "/user/signup/confirm?token=abc",
                "/user/signup/confirmUserMail?token=abc",
                "/user/supply/getAllCategoryNames",
                "/user/supply/getProductsFromCategory?categoryId=1&page=0&productRange=5",
                "/user/supply/getProductsFromSubcategory?subcategoryName=drinks&page=0&productRange=5",
                "/user/supply/getProductCountsFromSubcategory?subcategoryName=drinks",
                "/user/supply/getProductCountsFromCategory?categoryId=1",
                "/user/supply/getProductImageNames?productId=1",
                "/user/supply/getProductImage?productId=1",
                "/user/supply/getSubCategories?categoryId=1",
                "/user/supply/getProductDataWithProductId?productId=1",
                "/user/supply/getProductDescription?productId=1"
        };

        when(userSupplyService.getProductImage(any())).thenReturn(new ByteArrayResource("img".getBytes()));

        for (String route : publicGetRoutes) {
            mockMvc.perform(get(route))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void publicPostRoutesAreReachableAnonymously() throws Exception {
        when(userSignupService.login(any())).thenReturn("user-token");
        when(authCookieService.createUserSessionCookie("user-token"))
                .thenReturn(ResponseCookie.from(AuthCookies.USER_SESSION_COOKIE_NAME, "user-token").path("/").build());
        when(adminSignupService.login(any(), any())).thenReturn("admin-token");
        when(authCookieService.createAdminSessionCookie("admin-token"))
                .thenReturn(ResponseCookie.from(AuthCookies.ADMIN_SESSION_COOKIE_NAME, "admin-token").path("/admin").build());
        when(authCookieService.clearUserSessionCookie())
                .thenReturn(ResponseCookie.from(AuthCookies.USER_SESSION_COOKIE_NAME, "").path("/").build());
        when(authCookieService.clearAdminSessionCookie())
                .thenReturn(ResponseCookie.from(AuthCookies.ADMIN_SESSION_COOKIE_NAME, "").path("/admin").build());
        Event event = org.mockito.Mockito.mock(Event.class);
        when(stripeWebhookVerifier.verify(eq("{}"), eq("t=1,v1=valid"))).thenReturn(event);

        mockMvc.perform(post("/user/signup")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userMail\":\"user@example.com\",\"userPassword\":\"Strong123!\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/user/verifyUserMail?userMail=user@example.com").with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/user/resetPassword")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"token\",\"userPassword\":\"Strong123!\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/user/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userMail\":\"user@example.com\",\"userPassword\":\"Strong123!\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/user/logout").with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adminName\":\"admin\",\"adminPassword\":\"password\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/logout").with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/payment/webhook")
                        .header("Stripe-Signature", "t=1,v1=valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void internalBridgeRejectsMissingKey() throws Exception {
        mockMvc.perform(get("/internal/support/customers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalBridgeRejectsWrongKey() throws Exception {
        mockMvc.perform(get("/internal/support/customers")
                        .header("x-internal-key", "wrong-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalBridgeAcceptsCorrectKey() throws Exception {
        mockMvc.perform(get("/internal/support/customers")
                        .header("x-internal-key", "test-internal-key"))
                .andExpect(status().isOk());
    }
}
