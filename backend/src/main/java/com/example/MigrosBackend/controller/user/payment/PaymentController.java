package com.example.MigrosBackend.controller.user.payment;

import com.example.MigrosBackend.service.user.supply.UserOrderService;
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
    private final UserOrderService userOrderService;

    public PaymentController(UserOrderService userOrderService) {
        this.userOrderService = userOrderService;
    }

    static {
        // Set your secret key (from Stripe dashboard)
        Stripe.apiKey = "sk_test_51R5GK1RpCkckemuqtK5XI8QJxHsbBSGrRrnbDflhRsGruY72jTXPwn4loYYIIFiXN090DwSm174SEi3OMVCzZp0J00NtCoHtU0";
    }

    @PostMapping("/create-charge")
    public Map<String, Object> createCharge(@RequestBody Map<String, Object> payload) {
        String token = (String) payload.get("token");
        int amount = (int) payload.get("amount"); // Amount in cents
        String userToken = (String) payload.get("userToken");

        Map<String, Object> response = new HashMap<>();

        try {
            ChargeCreateParams params = ChargeCreateParams.builder()
                    .setAmount((long) amount)
                    .setCurrency("usd")
                    .setDescription("Example charge")
                    .setSource(token)
                    .build();

            Charge charge = Charge.create(params);

            // Manually extract relevant data from the charge object to return in the response
            Map<String, Object> chargeDetails = new HashMap<>();
            chargeDetails.put("id", charge.getId());
            chargeDetails.put("amount", charge.getAmount());
            chargeDetails.put("currency", charge.getCurrency());
            chargeDetails.put("status", charge.getStatus());
            chargeDetails.put("description", charge.getDescription());

            response.put("success", true);
            response.put("charge", chargeDetails);

            userOrderService.createOrder(userToken);
        } catch (StripeException e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("error", "Stripe error: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("error", "Unexpected error: " + e.getMessage());
        }

        return response;
    }
}
