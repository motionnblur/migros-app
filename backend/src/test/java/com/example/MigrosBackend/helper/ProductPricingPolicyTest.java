package com.example.MigrosBackend.helper;

import com.example.MigrosBackend.exception.shared.GeneralException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the effective-price arithmetic that used to be written out twice, in
 * {@code UserCatalogReadService.getEffectivePrice} and in
 * {@code CheckoutCalculations.effectiveUnitPrice}.
 *
 * <p>Expected values are literals rather than a second implementation on
 * purpose: this class is the one place the formula lives, so a copy of the
 * formula in a test would only prove it still equals itself. The two callers'
 * own tests hold the before/after comparison against the previous inline code.
 *
 * <p>Comparisons use {@link BigDecimal#equals(Object)}, not
 * {@code compareTo}: equals also compares scale, so a price that came back as
 * {@code 10.0} instead of {@code 10.00} fails here rather than passing a
 * numeric comparison and then changing what a JSON response serializes.
 */
class ProductPricingPolicyTest {

    /** {@code NUMERIC(19, 2)} holds seventeen integer digits: this is its maximum. */
    private static final String MAX_STORABLE_PRICE = "99999999999999999.99";

    @Test
    void aZeroDiscountReturnsThePriceNormalizedToTheMoneyScale() {
        assertEquals(new BigDecimal("10.00"), effective("10.00", "0"));
        assertEquals(new BigDecimal("10.00"), effective("10", "0"));
        assertEquals(new BigDecimal("10.50"), effective("10.5", "0.00"));
        assertEquals(new BigDecimal("0.00"), effective("0", "0"));
        assertEquals(new BigDecimal("0.00"), effective("0.004", "0"));
    }

    @Test
    void aHundredPercentDiscountReturnsZero() {
        assertEquals(new BigDecimal("0.00"), effective("10.00", "100"));
        assertEquals(new BigDecimal("0.00"), effective("10.00", "100.00"));
        assertEquals(new BigDecimal("0.00"), effective(MAX_STORABLE_PRICE, "100"));
    }

    @Test
    void aDiscountJustUnderAHundredPercentStillCollapsesToZero() {
        assertEquals(new BigDecimal("0.00"), effective("2.00", "99.99"));
    }

    @Test
    void anOrdinaryDiscountIsAppliedAtSixDecimalsThenRoundedToTheMoneyScale() {
        assertEquals(new BigDecimal("8.75"), effective("10.00", "12.5"));
        assertEquals(new BigDecimal("8.76"), effective("10.01", "12.5"));
        assertEquals(new BigDecimal("6.25"), effective("7.35", "15"));
        assertEquals(new BigDecimal("12222.21"), effective("12345.67", "1"));
        assertEquals(new BigDecimal("19.99"), effective("19.99", "0.01"));
    }

    /**
     * The three values around a half: 0.9949 rounds down, and 0.995 is exactly
     * half a cent and must round up, not to even and not down.
     */
    @Test
    void roundingIsHalfUpAtTheThirdDecimal() {
        assertEquals(new BigDecimal("0.99"), effective("1.00", "0.51"));
        assertEquals(new BigDecimal("1.00"), effective("1.00", "0.50"));
        assertEquals(new BigDecimal("1.00"), effective("1.00", "0.49"));
    }

    /** An exact half a cent at a large magnitude, where a float would lose it. */
    @Test
    void roundingIsHalfUpAtLargeMagnitudes() {
        assertEquals(new BigDecimal("500000000.03"), effective("1000000000.05", "50"));
        assertEquals(new BigDecimal("89999999999999999.99"), effective(MAX_STORABLE_PRICE, "10"));
    }

    /** The largest value the money column can hold prices without overflowing. */
    @Test
    void largeValidValuesArePricedExactly() {
        assertEquals(new BigDecimal(MAX_STORABLE_PRICE), effective(MAX_STORABLE_PRICE, "0"));
        assertEquals(new BigDecimal("1000000000.05"), effective("1000000000.05", "0"));
    }

    /**
     * The price itself is normalized with half-up rounding, so a value finer
     * than the money scale lands on the cent it rounds to. Checkout rejects
     * these before they arrive; catalog's display adaptation does not.
     */
    @Test
    void anOverPrecisePriceIsRoundedToTheMoneyScale() {
        assertEquals(new BigDecimal("10.01"), effective("10.005", "0"));
        assertEquals(new BigDecimal("10.00"), effective("10.004", "0"));
    }

    /**
     * The arithmetic does not invent a meaning for a discount it should never
     * receive. A negative percentage reads as no discount, which is what the
     * catalog listing has always displayed; an over-100 percent discount
     * produces a negative price rather than being silently capped. Both are
     * unreachable from checkout, which rejects them first, and both are pinned
     * here so the leniency is visible in one place instead of spread across two
     * callers that happened to disagree about it.
     */
    @Test
    void anUnvalidatedDiscountIsNotReinterpreted() {
        assertEquals(new BigDecimal("10.00"), effective("10.00", "-5"));
        assertEquals(new BigDecimal("-50.00"), effective("100.00", "150"));
    }

    @ParameterizedTest(name = "price {0} is payable")
    @CsvSource({
            "0",
            "0.00",
            "10",
            "10.0",
            "10.00",
            "0.01",
            "99999999999999999.99"
    })
    void aValidPriceIsReturnedUnchanged(String price) {
        assertEquals(new BigDecimal(price), ProductPricingPolicy.requireValidPrice(new BigDecimal(price), "Apple"));
    }

    @Test
    void aMissingNegativeOrOverPrecisePriceIsRejectedAndNeverBecomesZero() {
        assertEquals("Product has an invalid price: Apple", assertThrows(GeneralException.class,
                () -> ProductPricingPolicy.requireValidPrice(null, "Apple")).getMessage());
        assertEquals("Product has an invalid price: Apple", assertThrows(GeneralException.class,
                () -> ProductPricingPolicy.requireValidPrice(new BigDecimal("-0.01"), "Apple")).getMessage());
        assertEquals("Product has an invalid price: Apple", assertThrows(GeneralException.class,
                () -> ProductPricingPolicy.requireValidPrice(new BigDecimal("-10.00"), "Apple")).getMessage());
        assertEquals("Product has an invalid price: Apple", assertThrows(GeneralException.class,
                () -> ProductPricingPolicy.requireValidPrice(new BigDecimal("10.001"), "Apple")).getMessage());
    }

    /**
     * A price beyond what {@code NUMERIC(19, 2)} can hold cannot be stored, so
     * this policy is not the place that refuses it;
     * {@code ProductCreationPolicy.requireProductPrice} is. The value still
     * prices rather than overflowing, which keeps a bad row from throwing an
     * arithmetic error out of a listing.
     */
    @Test
    void aPriceBeyondTheStoredColumnStillPrices() {
        assertEquals(new BigDecimal("100000000000000000.00"),
                effective("100000000000000000.00", "0"));
    }

    @ParameterizedTest(name = "discount {0} is payable")
    @CsvSource({
            "0",
            "0.00",
            "12.5",
            "99.99",
            "100",
            "100.00"
    })
    void aValidDiscountIsReturnedUnchanged(String discount) {
        assertEquals(new BigDecimal(discount),
                ProductPricingPolicy.requireValidDiscount(new BigDecimal(discount), "Apple"));
    }

    /** An absent discount means no discount, which is a valid value. */
    @Test
    void anAbsentDiscountBecomesZero() {
        assertEquals(BigDecimal.ZERO, ProductPricingPolicy.requireValidDiscount(null, "Apple"));
    }

    @Test
    void aNegativeOrOverHundredPercentDiscountIsRejected() {
        assertEquals("Product has an invalid discount: Apple", assertThrows(GeneralException.class,
                () -> ProductPricingPolicy.requireValidDiscount(new BigDecimal("-0.01"), "Apple")).getMessage());
        assertEquals("Product has an invalid discount: Apple", assertThrows(GeneralException.class,
                () -> ProductPricingPolicy.requireValidDiscount(new BigDecimal("100.01"), "Apple")).getMessage());
    }

    /**
     * The two rules compose into the payable price, and a rejected field stops
     * the composition rather than contributing a zero to it.
     */
    @Test
    void aRejectedFieldIsNeverSubstitutedWithZero() {
        BigDecimal invalidPrice = new BigDecimal("10.001");
        assertThrows(GeneralException.class, () -> ProductPricingPolicy.effectivePrice(
                ProductPricingPolicy.requireValidPrice(invalidPrice, "Apple"),
                ProductPricingPolicy.requireValidDiscount(null, "Apple")));

        BigDecimal invalidDiscount = new BigDecimal("150");
        assertThrows(GeneralException.class, () -> ProductPricingPolicy.effectivePrice(
                ProductPricingPolicy.requireValidPrice(new BigDecimal("10.00"), "Apple"),
                ProductPricingPolicy.requireValidDiscount(invalidDiscount, "Apple")));
    }

    @Test
    void theStrictCompositionIsTheSameNumberTheLenientOneProducesForValidInput() {
        for (String[] valid : new String[][]{
                {"10.00", "0"}, {"10.01", "12.5"}, {"0.01", "50"}, {"1.00", "100"},
                {MAX_STORABLE_PRICE, "10"}, {"1000000000.05", "50"}, {"7.35", "15"}}) {
            BigDecimal strict = ProductPricingPolicy.effectivePrice(
                    ProductPricingPolicy.requireValidPrice(new BigDecimal(valid[0]), "Apple"),
                    ProductPricingPolicy.requireValidDiscount(new BigDecimal(valid[1]), "Apple"));
            BigDecimal lenient = effective(valid[0], valid[1]);
            assertEquals(lenient, strict, "valid product " + valid[0] + " at " + valid[1] + "%");
        }
    }

    @Test
    void everyReturnedPriceIsMoneyScaled() {
        for (String[] valid : new String[][]{
                {"10.00", "0"}, {"10.01", "12.5"}, {"1.00", "100"}, {MAX_STORABLE_PRICE, "10"}}) {
            assertEquals(2, effective(valid[0], valid[1]).scale());
        }
        assertEquals(2, ProductPricingPolicy.MONEY_SCALE);
        assertEquals(new BigDecimal("100"), ProductPricingPolicy.MAX_DISCOUNT_PERCENT);
    }

    private BigDecimal effective(String price, String discountPercent) {
        return ProductPricingPolicy.effectivePrice(new BigDecimal(price), new BigDecimal(discountPercent));
    }
}
