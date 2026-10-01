package com.example.MigrosBackend.helper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The unit price is the one number here that can be wrong in a way nobody notices.
 *
 * <p>A wrong package price is visible: a customer sees the wrong number. A wrong
 * unit price is a comparison, and a comparison that is off by a factor of a
 * thousand does not look wrong - it looks like a different, more expensive
 * product. So the cases below are the ones where a plausible reordering of the
 * arithmetic produces a plausible wrong answer: grams against kilograms,
 * millilitres against litres, a fractional item count, a half-way rounding, and
 * the divisions that would throw or silently yield infinity.
 */
class ProductUnitPricePolicyTest {

    @ParameterizedTest(name = "{0} for {1} {2} is {3} per {4}")
    @CsvSource({
            // Mass. G and KG both price per kilogram, so 500 G must agree with
            // 0.5 KG exactly rather than being a thousand times larger.
            "45.00, 500, G, 90.00, KG",
            "45.00, 0.5, KG, 90.00, KG",
            "12.50, 250, G, 50.00, KG",
            "12.50, 1, KG, 12.50, KG",
            "7.25, 0.125, KG, 58.00, KG",
            "7.25, 125, G, 58.00, KG",
            // Volume, same relationship between ML and L.
            "24.99, 500, ML, 49.98, L",
            "24.99, 0.5, L, 49.98, L",
            "10.00, 750, ML, 13.33, L",
            "10.00, 0.75, L, 13.33, L",
            // Items: no conversion, the amount is the count.
            "59.90, 6, ADET, 9.98, ADET",
            "59.90, 1, ADET, 59.90, ADET",
            // A larger package is a smaller unit price, for the same package price.
            "100.00, 1, KG, 100.00, KG",
            "100.00, 2, KG, 50.00, KG",
            "100.00, 2000, G, 50.00, KG"
    })
    void convertsTheAmountToItsBasisBeforeDividing(String price, String amount, String unit,
                                                   String expected, String expectedBasis) {
        ProductUnitPricePolicy.UnitPrice result = ProductUnitPricePolicy.unitPrice(
                new BigDecimal(price), new BigDecimal(amount), unit);

        assertEquals(0, new BigDecimal(expected).compareTo(result.amount()),
                price + " for " + amount + " " + unit);
        assertEquals(ProductPriceBasis.valueOf(expectedBasis), result.basis(),
                "the basis names the measure customers compare in, which is not always the "
                        + "measure the package is sold in");
    }

    /**
     * The rounding boundary, checked against the money scale rather than against a
     * hand-written decimal.
     *
     * <p>10.00 over 3 items is 3.333..., and the two plausible answers differ by a
     * cent. HALF_UP is the project's money rule and PostgreSQL's
     * {@code round(numeric, int)} agrees with it, so the stored answer has to be
     * the same one the price columns are written with.
     */
    @Test
    void roundsARepeatingQuotientHalfUpAtTheMoneyScale() {
        ProductUnitPricePolicy.UnitPrice result = ProductUnitPricePolicy.unitPrice(
                new BigDecimal("10.00"), new BigDecimal("3"), "ADET");

        assertEquals(0, new BigDecimal("3.33").compareTo(result.amount()));
        assertEquals(2, result.amount().scale(), "a unit price is a money figure like every other price");
    }

    /**
     * The exact half-way case, where HALF_UP and HALF_DOWN disagree.
     */
    @Test
    void roundsAnExactHalfAwayFromZero() {
        ProductUnitPricePolicy.UnitPrice result = ProductUnitPricePolicy.unitPrice(
                new BigDecimal("0.05"), new BigDecimal("1"), "ADET");

        assertEquals(0, new BigDecimal("0.05").compareTo(result.amount()));
    }

    /**
     * A conversion that lands exactly on the basis must not be rounded away by the
     * divide. 1000 G is 1 kg, not 1.000 kg and certainly not 0.999 kg.
     */
    @Test
    void convertsAThousandSmallUnitsToTheBasisWithoutLosingTheValue() {
        ProductUnitPricePolicy.UnitPrice result = ProductUnitPricePolicy.unitPrice(
                new BigDecimal("20.00"), new BigDecimal("1000"), "G");

        assertEquals(0, new BigDecimal("20.00").compareTo(result.amount()));
    }

    /**
     * A product whose price is zero has a unit price of zero, and that is
     * reported rather than hidden.
     *
     * <p>Hiding it would be the wrong kind of careful: it would make a free product
     * indistinguishable from one with no package data, which are different facts
     * and one of them is wrong.
     */
    @Test
    void reportsZeroForAZeroPriceRatherThanNothing() {
        ProductUnitPricePolicy.UnitPrice result = ProductUnitPricePolicy.unitPrice(
                BigDecimal.ZERO, new BigDecimal("1"), "KG");

        assertEquals(0, BigDecimal.ZERO.compareTo(result.amount()));
        assertEquals(ProductPriceBasis.KG, result.basis());
    }

    /**
     * No package size, so no unit price.
     *
     * <p>This is the state of almost the whole catalogue, and it is the case that
     * must produce an absent field rather than a number: a price per kilogram with
     * no kilograms behind it is a guess, and it is the exact failure this feature
     * exists to prevent.
     */
    @Test
    void producesNothingWhenThereIsNoPackageSize() {
        assertNull(ProductUnitPricePolicy.unitPrice(new BigDecimal("45.00"), null, null));
        assertNull(ProductUnitPricePolicy.unitPrice(new BigDecimal("45.00"), null, "KG"));
        assertNull(ProductUnitPricePolicy.unitPrice(new BigDecimal("45.00"), new BigDecimal("1"), null));
        assertNull(ProductUnitPricePolicy.unitPrice(new BigDecimal("45.00"), new BigDecimal("1"), "  "));
    }

    /**
     * An amount that cannot be divided by. Application validation refuses to store
     * one, and the display path refuses to render one, so a row that somehow holds
     * it cannot take down a whole catalogue page.
     */
    @ParameterizedTest
    @ValueSource(strings = {"0", "0.000", "-1", "-0.5"})
    void producesNothingForAnAmountThatCannotBeDividedBy(String amount) {
        assertNull(ProductUnitPricePolicy.unitPrice(
                new BigDecimal("45.00"), new BigDecimal(amount), "KG"));
    }

    /**
     * A unit outside the closed set yields no unit price and no basis.
     *
     * <p>Only reachable by a write that bypassed the creation policy, and the
     * reason it is not "best effort" is that any divisor invented here would be one
     * nobody chose.
     */
    @ParameterizedTest
    @ValueSource(strings = {"GRAM", "KILO", "pcs", "undefined", "null"})
    void producesNothingForAUnitNobodyDefined(String unit) {
        assertNull(ProductUnitPricePolicy.unitPrice(new BigDecimal("45.00"), new BigDecimal("500"), unit));
    }

    /**
     * The unit token is matched leniently on the way in, so a stored unit that was
     * written with different casing or padding still prices rather than silently
     * disappearing from the listing.
     */
    @ParameterizedTest
    @ValueSource(strings = {"kg", " KG ", "Kg", "kG"})
    void acceptsAUnitTokenWhateverItsCaseAndPadding(String unit) {
        ProductUnitPricePolicy.UnitPrice result = ProductUnitPricePolicy.unitPrice(
                new BigDecimal("60.00"), new BigDecimal("2"), unit);

        assertEquals(0, new BigDecimal("30.00").compareTo(result.amount()));
        assertEquals(ProductPriceBasis.KG, result.basis());
    }

    /**
     * A missing price is the catalog's own concern, but a unit price cannot be
     * computed from nothing, so it reports nothing rather than a division.
     */
    @Test
    void producesNothingWhenThereIsNoPrice() {
        assertNull(ProductUnitPricePolicy.unitPrice(null, new BigDecimal("1"), "KG"));
    }

    /**
     * The unit price is a ratio of two numbers the customer is also looking at, so
     * it must be built from the price the card shows rather than from the
     * pre-discount one.
     */
    @Test
    void pricesTheEffectivePriceNotTheListPrice() {
        BigDecimal effective = ProductPricingPolicy.effectivePrice(
                new BigDecimal("100.00"), new BigDecimal("20.00"));

        ProductUnitPricePolicy.UnitPrice result =
                ProductUnitPricePolicy.unitPrice(effective, new BigDecimal("1"), "KG");

        assertEquals(0, new BigDecimal("80.00").compareTo(result.amount()),
                "a unit price built from the undiscounted price would advertise a saving the "
                        + "customer does not get");
    }
}
