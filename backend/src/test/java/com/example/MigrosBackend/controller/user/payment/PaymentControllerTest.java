package com.example.MigrosBackend.controller.user.payment;

import com.example.MigrosBackend.config.GlobalExceptionHandler;
import com.example.MigrosBackend.config.security.AuthCookies;
import com.example.MigrosBackend.dto.payment.CheckoutResponseDto;
import com.example.MigrosBackend.dto.payment.CheckoutStatusDto;
import com.example.MigrosBackend.dto.payment.PaymentResponseDto;
import com.example.MigrosBackend.dto.payment.PaymentStatusDto;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.exception.shared.TokenNotFoundException;
import com.example.MigrosBackend.exception.user.CheckoutNotFoundException;
import com.example.MigrosBackend.exception.user.CheckoutConflictException;
import com.example.MigrosBackend.exception.user.CheckoutStateException;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.user.payment.CheckoutService;
import com.example.MigrosBackend.service.user.payment.UserPaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserPaymentService userPaymentService;

    @MockBean
    private CheckoutService checkoutService;

    @MockBean
    private AuthTokenResolver authTokenResolver;

    @MockBean
    private AdminEntityRepository adminEntityRepository;

    @MockBean
    private TokenService tokenService;

    private final UUID checkoutId = UUID.randomUUID();

    private CheckoutResponseDto prepared() {
        return new CheckoutResponseDto(checkoutId.toString(), "PREPARED", new BigDecimal("21.00"), 2100L, "try",
                LocalDateTime.now(), LocalDateTime.now().plusMinutes(15));
    }

    @Test
    void prepareCheckout_ReturnsServerCalculatedSnapshot() throws Exception {
        when(authTokenResolver.requireToken("sample-token")).thenReturn("sample-token");
        when(checkoutService.prepareCheckout("sample-token")).thenReturn(prepared());

        mockMvc.perform(post("/payment/checkouts")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "sample-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutId").value(checkoutId.toString()))
                .andExpect(jsonPath("$.totalAmount").value(21.00))
                .andExpect(jsonPath("$.amountMinor").value(2100))
                .andExpect(jsonPath("$.currency").value("try"));
    }

    @Test
    void prepareCheckout_ReturnsNotFoundWhenCookieMissing() throws Exception {
        when(authTokenResolver.requireToken(null)).thenThrow(new TokenNotFoundException());

        mockMvc.perform(post("/payment/checkouts"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getCheckout_ReturnsOwnedStatus() throws Exception {
        when(authTokenResolver.requireToken("sample-token")).thenReturn("sample-token");
        when(checkoutService.getCheckout("sample-token", checkoutId)).thenReturn(new CheckoutStatusDto(
                checkoutId.toString(), "CONSUMED", new BigDecimal("21.00"), 2100L, "try",
                LocalDateTime.now(), LocalDateTime.now(), 900L, "ch_1"));

        mockMvc.perform(get("/payment/checkouts/{checkoutId}", checkoutId)
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "sample-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONSUMED"))
                .andExpect(jsonPath("$.orderGroupId").value(900));
    }

    @Test
    void getCheckout_OtherOwnerOrMissingIsNotFound() throws Exception {
        when(authTokenResolver.requireToken("sample-token")).thenReturn("sample-token");
        when(checkoutService.getCheckout("sample-token", checkoutId)).thenThrow(new CheckoutNotFoundException());

        mockMvc.perform(get("/payment/checkouts/{checkoutId}", checkoutId)
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "sample-token")))
                .andExpect(status().isNotFound())
                .andExpect(content -> {
                    String body = content.getResponse().getContentAsString();
                    if (body.contains(checkoutId.toString())) {
                        throw new AssertionError("ownership failure must not reveal the checkout id");
                    }
                });
    }

    @Test
    void charge_UsesPathCheckoutIdAndTokenOnly() throws Exception {
        when(authTokenResolver.requireToken("sample-token")).thenReturn("sample-token");
        when(userPaymentService.processCharge(eq(checkoutId), eq("tok_visa"), eq("sample-token")))
                .thenReturn(new PaymentResponseDto(true, false, checkoutId.toString(),
                        UUID.randomUUID().toString(), "CONSUMED", "ORDER_FINALIZED", "ch_1",
                        new BigDecimal("21.00"), 2100L, "try", null));

        mockMvc.perform(post("/payment/checkouts/{checkoutId}/charge", checkoutId)
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "sample-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok_visa\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.chargeId").value("ch_1"));
    }

    @Test
    void paymentStatus_ReturnsRecoverableState() throws Exception {
        when(authTokenResolver.requireToken("sample-token")).thenReturn("sample-token");
        when(userPaymentService.getPaymentStatus("sample-token", checkoutId)).thenReturn(
                new PaymentStatusDto(checkoutId.toString(), UUID.randomUUID().toString(), "PAYMENT_PROCESSING",
                        "CHARGE_SUCCEEDED", "ch_1", new BigDecimal("21.00"), 2100L, "try", null,
                        false, true, false));

        mockMvc.perform(get("/payment/checkouts/{checkoutId}/status", checkoutId)
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "sample-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(true))
                .andExpect(jsonPath("$.state").value("CHARGE_SUCCEEDED"));
    }

    @Test
    void charge_WithBlankTokenIsRejected() throws Exception {
        when(authTokenResolver.requireToken("sample-token")).thenReturn("sample-token");
        when(userPaymentService.processCharge(eq(checkoutId), eq("  "), eq("sample-token")))
                .thenThrow(new GeneralException("Payment token is required"));

        mockMvc.perform(post("/payment/checkouts/{checkoutId}/charge", checkoutId)
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "sample-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancel_ReturnsConflictForPaidCheckout() throws Exception {
        when(authTokenResolver.requireToken("sample-token")).thenReturn("sample-token");
        when(checkoutService.cancelCheckout("sample-token", checkoutId))
                .thenThrow(CheckoutConflictException.notCancellable(
                        checkoutId, "A paid checkout cannot be cancelled"));

        mockMvc.perform(post("/payment/checkouts/{checkoutId}/cancel", checkoutId)
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "sample-token")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("CHECKOUT_NOT_CANCELLABLE"))
                .andExpect(jsonPath("$.pending").value(false))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.checkoutId").value(checkoutId.toString()));
    }

    @Test
    void cancel_ReleasesUnpaidReservation() throws Exception {
        when(authTokenResolver.requireToken("sample-token")).thenReturn("sample-token");
        when(checkoutService.cancelCheckout("sample-token", checkoutId)).thenReturn(new CheckoutStatusDto(
                checkoutId.toString(), "CANCELLED", new BigDecimal("21.00"), 2100L, "try",
                LocalDateTime.now(), LocalDateTime.now(), null, null));

        mockMvc.perform(post("/payment/checkouts/{checkoutId}/cancel", checkoutId)
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "sample-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancel_ReturnsConflictForProcessingCheckout() throws Exception {
        when(authTokenResolver.requireToken("sample-token")).thenReturn("sample-token");
        when(checkoutService.cancelCheckout("sample-token", checkoutId))
                .thenThrow(CheckoutConflictException.reconciliationPending(
                        checkoutId,
                        "Payment is still processing and cannot be cancelled; check payment status"));

        String body = mockMvc.perform(post("/payment/checkouts/{checkoutId}/cancel", checkoutId)
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "sample-token")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("PAYMENT_RECONCILIATION_PENDING"))
                .andExpect(jsonPath("$.pending").value(true))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.checkoutId").value(checkoutId.toString()))
                .andExpect(jsonPath("$.message").exists())
                .andReturn().getResponse().getContentAsString();

        String lowered = body.toLowerCase();
        if (lowered.contains("stripe") || lowered.contains("ch_") || lowered.contains("lease")
                || lowered.contains("charge")) {
            throw new AssertionError("conflict response must not leak provider details: " + body);
        }
    }

    @Test
    void cancel_MapsGenericCheckoutConflictToTypedContract() throws Exception {
        when(authTokenResolver.requireToken("sample-token")).thenReturn("sample-token");
        when(checkoutService.cancelCheckout("sample-token", checkoutId))
                .thenThrow(new CheckoutStateException("Checkout is not payable"));

        mockMvc.perform(post("/payment/checkouts/{checkoutId}/cancel", checkoutId)
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "sample-token")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("CHECKOUT_CONFLICT"))
                .andExpect(jsonPath("$.pending").value(false))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void cancel_ReturnsNotFoundWhenCookieMissing() throws Exception {
        when(authTokenResolver.requireToken(null)).thenThrow(new TokenNotFoundException());

        mockMvc.perform(post("/payment/checkouts/{checkoutId}/cancel", checkoutId))
                .andExpect(status().isNotFound());

        verify(checkoutService, never()).cancelCheckout(any(), any());
    }

    @Test
    void cancel_OtherOwnerOrMissingIsNotFound() throws Exception {
        when(authTokenResolver.requireToken("sample-token")).thenReturn("sample-token");
        when(checkoutService.cancelCheckout("sample-token", checkoutId))
                .thenThrow(new com.example.MigrosBackend.exception.user.CheckoutNotFoundException());

        mockMvc.perform(post("/payment/checkouts/{checkoutId}/cancel", checkoutId)
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "sample-token")))
                .andExpect(status().isNotFound());
    }

    @Test
    void prepareCheckout_DoesNotTrustBodyTotals() throws Exception {
        when(authTokenResolver.requireToken("sample-token")).thenReturn("sample-token");
        when(checkoutService.prepareCheckout("sample-token")).thenReturn(prepared());

        mockMvc.perform(post("/payment/checkouts")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "sample-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"totalAmount\":0.01,\"amountMinor\":1,\"currency\":\"usd\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(21.00))
                .andExpect(jsonPath("$.currency").value("try"));

        verify(checkoutService, never()).cancelCheckout(any(), any());
    }

    @Test
    void charge_ReturnsTypedBadRequestForMalformedJson() throws Exception {
        when(authTokenResolver.requireToken("sample-token")).thenReturn("sample-token");

        mockMvc.perform(post("/payment/checkouts/{checkoutId}/charge", checkoutId)
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "sample-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-valid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }
}
