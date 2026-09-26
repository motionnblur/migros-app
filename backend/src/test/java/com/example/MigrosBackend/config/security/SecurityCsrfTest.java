package com.example.MigrosBackend.config.security;

import com.example.MigrosBackend.config.GlobalExceptionHandler;
import com.example.MigrosBackend.controller.security.CsrfController;
import com.example.MigrosBackend.controller.internal.InternalSupportController;
import com.example.MigrosBackend.controller.user.payment.StripeWebhookController;
import com.example.MigrosBackend.dto.support.InternalSupportAgentMessageDto;
import com.example.MigrosBackend.exception.user.WebhookSignatureException;
import com.example.MigrosBackend.filter.JwtRequestFilter;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.support.SupportChatService;
import com.example.MigrosBackend.service.user.payment.PaymentWebhookService;
import com.example.MigrosBackend.service.user.payment.StripeWebhookVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.stripe.model.Event;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        CsrfController.class,
        CsrfMutationProbeController.class,
        StripeWebhookController.class,
        InternalSupportController.class
})
@AutoConfigureMockMvc
@Import({SecurityConfiguration.class, JwtRequestFilter.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "support.internal.key=test-internal-key",
        "auth.cookie.secure=true",
        "auth.cookie.same-site=None",
        "app.allowed-origins=http://trusted.example",
        "app.allowed-origin-patterns="
})
class SecurityCsrfTest {

    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";
    private static final String USER_TOKEN = "user.jwt.token";
    private static final String TRUSTED_ORIGIN = "http://trusted.example";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CookieCsrfTokenRepository csrfTokenRepository;

    @MockBean
    private TokenService tokenService;

    @MockBean
    private AdminEntityRepository adminEntityRepository;

    @MockBean
    private StripeWebhookVerifier stripeWebhookVerifier;

    @MockBean
    private PaymentWebhookService paymentWebhookService;

    @MockBean
    private SupportChatService supportChatService;

    @Test
    void csrfEndpointReturnsTokenAndSetsRepositoryCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.headerName").value(CSRF_HEADER_NAME))
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie, "Expected the CSRF repository cookie to be written");
        assertTrue(setCookie.startsWith(CSRF_COOKIE_NAME + "="), setCookie);
        assertTrue(setCookie.contains("Secure"), setCookie);
        assertTrue(setCookie.contains("Path=/"), setCookie);
        assertFalse(setCookie.toLowerCase().contains("httponly"), setCookie);
    }

    @Test
    void csrfRepositoryCookieUsesCrossSiteCompatibleAttributes() {
        // MockMvc renders Servlet 6 addCookie cookies through jakarta Cookie,
        // which drops the SameSite attribute from the printed header. Exercise
        // the repository directly on the Servlet 5 addHeader path so every
        // configured attribute is observable, exactly as a real container emits
        // it.
        MockServletContext servletContext = new MockServletContext();
        servletContext.setMajorVersion(5);
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        MockHttpServletResponse response = new MockHttpServletResponse();

        csrfTokenRepository.saveToken(
                new DefaultCsrfToken(CSRF_HEADER_NAME, "_csrf", "raw-csrf-token"),
                request,
                response);

        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie);
        assertTrue(setCookie.startsWith(CSRF_COOKIE_NAME + "="), setCookie);
        assertTrue(setCookie.contains("Secure"), setCookie);
        assertTrue(setCookie.contains("SameSite=None"), setCookie);
        assertTrue(setCookie.contains("Path=/"), setCookie);
        assertFalse(setCookie.toLowerCase().contains("httponly"), setCookie);
    }

    @Test
    void authenticatedMutationWithoutTokenIsForbidden() throws Exception {
        when(tokenService.validateAndExtractUser(USER_TOKEN)).thenReturn("user@example.com");

        mockMvc.perform(post("/user/profile/csrf-probe/mutate")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, USER_TOKEN)))
                .andExpect(status().isForbidden());
    }

    @Test
    void authenticatedMutationWithMatchingTokenSucceeds() throws Exception {
        when(tokenService.validateAndExtractUser(USER_TOKEN)).thenReturn("user@example.com");

        MvcResult csrfResult = obtainCsrfToken();

        mockMvc.perform(post("/user/profile/csrf-probe/mutate")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, USER_TOKEN))
                        .cookie(new Cookie(CSRF_COOKIE_NAME, csrfCookieValue(csrfResult)))
                        .header(CSRF_HEADER_NAME, csrfToken(csrfResult)))
                .andExpect(status().isOk())
                .andExpect(content().string("mutated"));
    }

    @Test
    void mismatchedTokenIsForbidden() throws Exception {
        when(tokenService.validateAndExtractUser(USER_TOKEN)).thenReturn("user@example.com");

        MvcResult csrfResult = obtainCsrfToken();

        mockMvc.perform(post("/user/profile/csrf-probe/mutate")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, USER_TOKEN))
                        .cookie(new Cookie(CSRF_COOKIE_NAME, csrfCookieValue(csrfResult)))
                        .header(CSRF_HEADER_NAME, "not-the-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingTokenReturnsStableCsrfErrorCode() throws Exception {
        when(tokenService.validateAndExtractUser(USER_TOKEN)).thenReturn("user@example.com");

        mockMvc.perform(post("/user/profile/csrf-probe/mutate")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, USER_TOKEN)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void mismatchedTokenReturnsStableCsrfErrorCode() throws Exception {
        when(tokenService.validateAndExtractUser(USER_TOKEN)).thenReturn("user@example.com");

        MvcResult csrfResult = obtainCsrfToken();

        mockMvc.perform(post("/user/profile/csrf-probe/mutate")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, USER_TOKEN))
                        .cookie(new Cookie(CSRF_COOKIE_NAME, csrfCookieValue(csrfResult)))
                        .header(CSRF_HEADER_NAME, "not-the-token"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void ordinaryForbiddenResponseIsNotLabeledAsCsrfFailure() throws Exception {
        mockMvc.perform(get("/admin/panel/csrf-probe/read"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void untrustedOriginCannotReadCsrfToken() throws Exception {
        mockMvc.perform(get("/csrf")
                        .header(HttpHeaders.ORIGIN, "http://evil.example"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void trustedOriginCanReadCsrfToken() throws Exception {
        mockMvc.perform(get("/csrf")
                        .header(HttpHeaders.ORIGIN, TRUSTED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, TRUSTED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void stripeWebhookDoesNotRequireCsrfButStillRequiresValidSignature() throws Exception {
        Event event = mock(Event.class);
        when(stripeWebhookVerifier.verify(eq("{}"), eq("t=1,v1=valid"))).thenReturn(event);

        mockMvc.perform(post("/payment/webhook")
                        .header("Stripe-Signature", "t=1,v1=valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        when(stripeWebhookVerifier.verify(any(), eq("t=1,v1=bad")))
                .thenThrow(new WebhookSignatureException("Invalid Stripe webhook signature"));

        mockMvc.perform(post("/payment/webhook")
                        .header("Stripe-Signature", "t=1,v1=bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void internalSupportPostDoesNotRequireCsrfButStillRequiresInternalKey() throws Exception {
        InternalSupportAgentMessageDto dto = new InternalSupportAgentMessageDto(
                "user@test.com", "Hello", "agent-123");
        doNothing().when(supportChatService)
                .addManagementMessage("user@test.com", "Hello", "agent-123");

        mockMvc.perform(post("/internal/support/agent-message")
                        .header("x-internal-key", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/internal/support/agent-message")
                        .header("x-internal-key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void safeGetRequestsRemainUnaffected() throws Exception {
        when(tokenService.validateAndExtractUser(USER_TOKEN)).thenReturn("user@example.com");

        mockMvc.perform(get("/user/profile/csrf-probe/read")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, USER_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(content().string("read"));
    }

    private MvcResult obtainCsrfToken() throws Exception {
        return mockMvc.perform(get("/csrf"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String csrfToken(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private String csrfCookieValue(MvcResult result) {
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie, "Expected the CSRF repository cookie to be written");
        String value = setCookie.substring(setCookie.indexOf('=') + 1);
        int separator = value.indexOf(';');
        return separator >= 0 ? value.substring(0, separator) : value;
    }
}
