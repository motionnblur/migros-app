package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.CheckoutPaymentStart;
import com.example.MigrosBackend.dto.payment.CheckoutStatusDto;
import com.example.MigrosBackend.dto.payment.PaymentResponseDto;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class UserPaymentService {
    private final CheckoutService checkoutService;
    private final StripePaymentGateway stripePaymentGateway;

    public UserPaymentService(CheckoutService checkoutService,
                              StripePaymentGateway stripePaymentGateway) {
        this.checkoutService = checkoutService;
        this.stripePaymentGateway = stripePaymentGateway;
    }

    public PaymentResponseDto processCharge(UUID checkoutId, String paymentToken, String userToken) {
        if (paymentToken == null || paymentToken.isBlank()) {
            throw new GeneralException("Payment token is required");
        }

        //Claims the owned checkout for this attempt and commits the reservation
        //state before any network call, so no database transaction is held open
        //while Stripe is contacted.
        CheckoutPaymentStart start = checkoutService.beginPayment(userToken, checkoutId);

        try {
            Charge charge = stripePaymentGateway.charge(paymentToken, start.amountMinor(), start.currency());
            try {
                CheckoutStatusDto status = checkoutService.completePayment(checkoutId, charge.getId());
                return new PaymentResponseDto(
                        true,
                        false,
                        status.checkoutId(),
                        status.status(),
                        status.chargeId(),
                        status.totalAmount(),
                        status.amountMinor(),
                        status.currency(),
                        null);
            } catch (RuntimeException finalizationError) {
                // Stripe accepted the charge. The reservation must not be
                // released and the outcome must stay recoverable rather than be
                // reported as an ordinary decline.
                return new PaymentResponseDto(
                        true,
                        true,
                        start.checkoutId().toString(),
                        "PAID",
                        charge.getId(),
                        null,
                        start.amountMinor(),
                        start.currency(),
                        "Payment succeeded but order finalization is pending");
            }
        } catch (StripeException e) {
            checkoutService.failPayment(checkoutId);
            return new PaymentResponseDto(
                    false,
                    false,
                    start.checkoutId().toString(),
                    "CANCELLED",
                    null,
                    null,
                    start.amountMinor(),
                    start.currency(),
                    "Stripe error: " + e.getMessage());
        }
    }
}
