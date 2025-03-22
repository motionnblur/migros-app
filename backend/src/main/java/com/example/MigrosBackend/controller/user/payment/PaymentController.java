package com.example.MigrosBackend.controller.user.payment;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.param.ChargeCreateParams;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/payment")
public class PaymentController {

    static {
        // Set your secret key (from Stripe dashboard)
        Stripe.apiKey = "pk_test_51R5GK1RpCkckemuqxqwmtU3jtnARLIiSxsxaeU8lg7wQrJJH8oUxH5ZdykHQCRvFNvSL4duOLcL6XQY5Cwkxjcvp00VDagc07P";
    }

    @PostMapping("/create-charge")
    public Map<String, Object> createCharge(@RequestBody Map<String, Object> payload) {
        String token = (String) payload.get("token");
        int amount = (int) payload.get("amount"); // Amount in cents

        Map<String, Object> response = new HashMap<>();

        try {
            ChargeCreateParams params = ChargeCreateParams.builder()
                    .setAmount((long) amount)
                    .setCurrency("usd")
                    .setDescription("Example charge")
                    .setSource(token)
                    .build();

            Charge charge = Charge.create(params);
            response.put("success", true);
            response.put("charge", charge);
        } catch (StripeException e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("error", e.getMessage());
        }

        return response;
    }
}
