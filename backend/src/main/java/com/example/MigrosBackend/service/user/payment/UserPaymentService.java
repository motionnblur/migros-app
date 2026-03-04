package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.service.user.supply.UserOrderService;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.param.ChargeCreateParams;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class UserPaymentService {
    private final UserOrderService userOrderService;

    public UserPaymentService(UserOrderService userOrderService) {
        this.userOrderService = userOrderService;
    }

    public Map<String, Object> processCharge(Map<String, Object> payload) {
        String token = (String) payload.get("token");
        String userToken = (String) payload.get("userToken");

        float amount = userOrderService.getOrderPrice(userToken);

        if (amount <= 0) {
            return null;
        }

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
            response.put("charge", extractChargeDetails(charge));

            userOrderService.createOrder(userToken);

        } catch (StripeException e) {
            response.put("success", false);
            response.put("error", "Stripe error: " + e.getMessage());
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", "Unexpected error: " + e.getMessage());
        }

        return response;
    }

    private Map<String, Object> extractChargeDetails(Charge charge) {
        Map<String, Object> details = new HashMap<>();
        details.put("id", charge.getId());
        details.put("amount", charge.getAmount());
        details.put("currency", charge.getCurrency());
        details.put("status", charge.getStatus());
        details.put("description", charge.getDescription());
        return details;
    }
}
