package com.example.MigrosBackend.controller.user.payment;

import com.example.MigrosBackend.config.security.AuthCookies;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.service.user.payment.UserPaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/payment")
public class PaymentController {
    private final UserPaymentService userPaymentService;
    private final AuthTokenResolver authTokenResolver;

    public PaymentController(UserPaymentService userPaymentService, AuthTokenResolver authTokenResolver) {
        this.userPaymentService = userPaymentService;
        this.authTokenResolver = authTokenResolver;
    }

    @PostMapping("/create-charge")
    public ResponseEntity<Map<String, Object>> createCharge(@RequestBody Map<String, Object> payload,
                                                            @CookieValue(name = AuthCookies.USER_SESSION_COOKIE_NAME, required = false) String token) {
        String userToken = authTokenResolver.requireToken(token);
        return ResponseEntity.ok(userPaymentService.processCharge(payload, userToken));
    }
}
