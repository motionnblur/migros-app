package com.example.MigrosBackend.controller.user.payment;

import com.example.MigrosBackend.config.security.AuthCookies;
import com.example.MigrosBackend.dto.payment.ChargeRequestDto;
import com.example.MigrosBackend.dto.payment.CheckoutResponseDto;
import com.example.MigrosBackend.dto.payment.CheckoutStatusDto;
import com.example.MigrosBackend.dto.payment.PaymentResponseDto;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.service.user.payment.CheckoutService;
import com.example.MigrosBackend.service.user.payment.UserPaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/payment")
public class PaymentController {
    private final UserPaymentService userPaymentService;
    private final CheckoutService checkoutService;
    private final AuthTokenResolver authTokenResolver;

    public PaymentController(UserPaymentService userPaymentService,
                             CheckoutService checkoutService,
                             AuthTokenResolver authTokenResolver) {
        this.userPaymentService = userPaymentService;
        this.checkoutService = checkoutService;
        this.authTokenResolver = authTokenResolver;
    }

    @PostMapping("/checkouts")
    public ResponseEntity<CheckoutResponseDto> prepareCheckout(
            @CookieValue(name = AuthCookies.USER_SESSION_COOKIE_NAME, required = false) String token) {
        String userToken = authTokenResolver.requireToken(token);
        return ResponseEntity.ok(checkoutService.prepareCheckout(userToken));
    }

    @GetMapping("/checkouts/{checkoutId}")
    public ResponseEntity<CheckoutStatusDto> getCheckout(
            @PathVariable UUID checkoutId,
            @CookieValue(name = AuthCookies.USER_SESSION_COOKIE_NAME, required = false) String token) {
        String userToken = authTokenResolver.requireToken(token);
        return ResponseEntity.ok(checkoutService.getCheckout(userToken, checkoutId));
    }

    @PostMapping("/checkouts/{checkoutId}/charge")
    public ResponseEntity<PaymentResponseDto> charge(
            @PathVariable UUID checkoutId,
            @RequestBody(required = false) ChargeRequestDto request,
            @CookieValue(name = AuthCookies.USER_SESSION_COOKIE_NAME, required = false) String token) {
        String userToken = authTokenResolver.requireToken(token);
        String paymentToken = request == null ? null : request.token();
        return ResponseEntity.ok(userPaymentService.processCharge(checkoutId, paymentToken, userToken));
    }

    @PostMapping("/checkouts/{checkoutId}/cancel")
    public ResponseEntity<CheckoutStatusDto> cancelCheckout(
            @PathVariable UUID checkoutId,
            @CookieValue(name = AuthCookies.USER_SESSION_COOKIE_NAME, required = false) String token) {
        String userToken = authTokenResolver.requireToken(token);
        return ResponseEntity.ok(checkoutService.cancelCheckout(userToken, checkoutId));
    }
}
