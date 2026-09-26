package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.service.user.supply.UserOrderService;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Service
public class UserPaymentService {
    private final UserOrderService userOrderService;
    private final PaymentAmountConverter paymentAmountConverter;
    private final StripePaymentGateway stripePaymentGateway;

    public UserPaymentService(UserOrderService userOrderService,
                              PaymentAmountConverter paymentAmountConverter,
                              StripePaymentGateway stripePaymentGateway) {
        this.userOrderService = userOrderService;
        this.paymentAmountConverter = paymentAmountConverter;
        this.stripePaymentGateway = stripePaymentGateway;
    }

    public Map<String, Object> processCharge(Map<String, Object> payload, String userToken) {
        String token = (String) payload.get("token");

        BigDecimal amount = userOrderService.getOrderPrice(userToken);
        StripeAmount stripeAmount = paymentAmountConverter.toStripeAmount(amount);

        Map<String, Object> response = new HashMap<>();

        try {
            Charge charge = stripePaymentGateway.charge(
                    token, stripeAmount.amountMinor(), stripeAmount.currency());

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
