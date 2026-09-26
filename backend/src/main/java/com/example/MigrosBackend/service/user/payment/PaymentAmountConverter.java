package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.exception.user.PaymentAmountException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

@Component
public class PaymentAmountConverter {
    private static final int MINOR_UNIT_SCALE = 2;
    private static final String SUPPORTED_CURRENCY = "try";
    private static final long MAX_MINOR_UNITS = 99_999_999L;

    private final String currency;

    public PaymentAmountConverter(@Value("${payment.currency:try}") String currency) {
        this.currency = normalizeCurrency(currency);
    }

    public StripeAmount toStripeAmount(BigDecimal majorAmount) {
        if (majorAmount == null) {
            throw new PaymentAmountException("Payment amount is required");
        }

        BigDecimal significant = majorAmount.stripTrailingZeros();
        if (significant.scale() > MINOR_UNIT_SCALE) {
            throw new PaymentAmountException("Payment amount must not exceed two decimal places");
        }

        BigDecimal normalized = majorAmount.setScale(MINOR_UNIT_SCALE, RoundingMode.HALF_UP);
        if (normalized.signum() <= 0) {
            throw new PaymentAmountException("Payment amount must be positive");
        }

        long minorUnits;
        try {
            minorUnits = normalized.movePointRight(MINOR_UNIT_SCALE).longValueExact();
        } catch (ArithmeticException e) {
            throw new PaymentAmountException("Payment amount is out of range");
        }

        if (minorUnits > MAX_MINOR_UNITS) {
            throw new PaymentAmountException(
                    "Payment amount exceeds the maximum supported for " + SUPPORTED_CURRENCY);
        }

        return new StripeAmount(minorUnits, currency);
    }

    private static String normalizeCurrency(String currency) {
        if (currency == null) {
            throw new IllegalStateException("payment.currency must be configured");
        }

        String normalized = currency.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalStateException("payment.currency must not be blank");
        }
        if (!SUPPORTED_CURRENCY.equals(normalized)) {
            throw new IllegalStateException(
                    "Unsupported payment currency '" + currency + "'; only TRY is supported until "
                    + "currency-specific minor-unit handling is implemented");
        }

        return normalized;
    }
}
