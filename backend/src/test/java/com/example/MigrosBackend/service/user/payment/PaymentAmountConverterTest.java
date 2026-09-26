package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.exception.user.PaymentAmountException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentAmountConverterTest {
    private final PaymentAmountConverter converter = new PaymentAmountConverter("try");

    @Test
    void convertsWholeMajorUnitsToMinorUnits() {
        StripeAmount amount = converter.toStripeAmount(new BigDecimal("50.00"));

        assertEquals(5000L, amount.amountMinor());
        assertEquals("try", amount.currency());
    }

    @Test
    void acceptsTryCurrency() {
        StripeAmount amount = new PaymentAmountConverter("try")
                .toStripeAmount(new BigDecimal("50.00"));

        assertEquals("try", amount.currency());
    }

    @Test
    void normalizesUppercaseTryToLowercase() {
        StripeAmount amount = new PaymentAmountConverter("TRY")
                .toStripeAmount(new BigDecimal("50.00"));

        assertEquals("try", amount.currency());
    }

    @Test
    void normalizesSurroundingWhitespace() {
        StripeAmount amount = new PaymentAmountConverter("  TrY  ")
                .toStripeAmount(new BigDecimal("50.00"));

        assertEquals("try", amount.currency());
    }

    @Test
    void rejectsUnsupportedCurrency() {
        assertThrows(IllegalStateException.class, () -> new PaymentAmountConverter("usd"));
    }

    @Test
    void rejectsBlankCurrency() {
        assertThrows(IllegalStateException.class, () -> new PaymentAmountConverter("   "));
        assertThrows(IllegalStateException.class, () -> new PaymentAmountConverter(null));
    }

    @Test
    void preservesKurusWithoutTruncation() {
        assertEquals(5099L, converter.toStripeAmount(new BigDecimal("50.99")).amountMinor());
    }

    @Test
    void convertsFiveHundredTryToFiftyThousandMinorUnits() {
        StripeAmount amount = converter.toStripeAmount(new BigDecimal("500.00"));

        assertEquals(50000L, amount.amountMinor());
        assertEquals("try", amount.currency());
    }

    @Test
    void acceptsTrailingZeroPaddingWithinTwoDecimalPlaces() {
        assertEquals(1000L, converter.toStripeAmount(new BigDecimal("10.000")).amountMinor());
        assertEquals(1000L, converter.toStripeAmount(new BigDecimal("10.00")).amountMinor());
    }

    @Test
    void rejectsInputWithMoreThanTwoMeaningfulDecimalPlaces() {
        assertThrows(PaymentAmountException.class,
                () -> converter.toStripeAmount(new BigDecimal("50.994")));
        assertThrows(PaymentAmountException.class,
                () -> converter.toStripeAmount(new BigDecimal("50.995")));
        assertThrows(PaymentAmountException.class,
                () -> converter.toStripeAmount(new BigDecimal("10.001")));
    }

    @Test
    void acceptsAmountAtStripeTryEightDigitLimit() {
        StripeAmount amount = converter.toStripeAmount(new BigDecimal("999999.99"));

        assertEquals(99999999L, amount.amountMinor());
    }

    @Test
    void rejectsAmountAboveStripeTryEightDigitLimit() {
        assertThrows(PaymentAmountException.class,
                () -> converter.toStripeAmount(new BigDecimal("1000000.00")));
    }

    @Test
    void rejectsZeroAmount() {
        assertThrows(PaymentAmountException.class,
                () -> converter.toStripeAmount(BigDecimal.ZERO));
    }

    @Test
    void rejectsNegativeAmount() {
        assertThrows(PaymentAmountException.class,
                () -> converter.toStripeAmount(new BigDecimal("-1.00")));
    }

    @Test
    void rejectsNullAmount() {
        assertThrows(PaymentAmountException.class,
                () -> converter.toStripeAmount(null));
    }

    @Test
    void rejectsAmountThatDoesNotFitStripeIntegerField() {
        assertThrows(PaymentAmountException.class,
                () -> converter.toStripeAmount(new BigDecimal("1E20")));
    }
}
