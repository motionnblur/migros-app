package com.example.MigrosBackend.controller.user.payment;

import com.example.MigrosBackend.service.user.payment.UserPaymentService;
import com.example.MigrosBackend.service.user.supply.UserOrderService;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.param.ChargeCreateParams;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/payment")
public class PaymentController {
    private final UserPaymentService userPaymentService;

    public PaymentController(UserPaymentService userPaymentService) {
        this.userPaymentService = userPaymentService;
    }

    static {
        // Set your secret key (from Stripe dashboard)
        Stripe.apiKey = "sk_test_51R5GK1RpCkckemuqtK5XI8QJxHsbBSGrRrnbDflhRsGruY72jTXPwn4loYYIIFiXN090DwSm174SEi3OMVCzZp0J00NtCoHtU0";
    }

    @PostMapping("/create-charge")
    public ResponseEntity<Map<String, Object>> createCharge(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(userPaymentService.processCharge(payload));
    }
}
