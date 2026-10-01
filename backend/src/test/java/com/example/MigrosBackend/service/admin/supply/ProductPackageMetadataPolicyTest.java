package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.helper.ProductPricingPolicy;
import com.example.MigrosBackend.helper.ProductUnitPricePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The package rules, checked on the policy itself rather than through an endpoint.
 *
 * <p>Two of the three entry points (JSON creation, multipart creation, the
 * version-checked edit) each have their own transport test elsewhere; what is
 * <em>not</em> per-transport is the set of values a package size may take, and that
 * set is exactly what a second implementation of the rules would get subtly
 * different. So it is asserted once, here, against the single owner - and against
 * the whole boundary of the column rather than a handful of examples.
 *
 * <p>Every rejection here is the one the administrator sees, so the message is part
 * of what is being tested: a refusal whose text names a range is actionable, and a
 * refusal that says "invalid value" is not.
 */
class ProductPackageMetadataPolicyTest {

    private final ProductCreationPolicy policy = new ProductCreationPolicy();

    private static ProductDetails details(String amount, String unit) {
        return ProductDetails.of("Milk", "Dairy", new BigDecimal("12.50"), 4,
                BigDecimal.ZERO, "Fresh",
                amount == null ? null : new BigDecimal(amount), unit);
    }

    // -------------------------------------------------------------------------
    // The absent case, which is the ordinary one
    // -------------------------------------------------------------------------

    /**
     * Most of the catalogue has no package size, so absent has to be a first-class
     * value rather than a rejected one or a defaulted zero.
     */
    @Test
    void aProductWithoutAPackageSizeIsStoredWithoutOne() {
        ProductDetails normalized = policy.normalize(details(null, null));

        assertNull(normalized.packageAmount());
        assertNull(normalized.packageUnit());
    }

    /**
     * An empty unit is what an untouched multipart form field binds to, and it has
     * to mean the same as an absent one. If it did not, every product created
     * through the admin form would be rejected until the field was cleared.
     */
    @Test
    void anEmptyUnitIsTheSameAsNoUnit() {
        ProductDetails normalized = policy.normalize(details(null, "   "));

        assertNull(normalized.packageAmount());
        assertNull(normalized.packageUnit());
    }

    // -------------------------------------------------------------------------
    // The pair rule
    // -------------------------------------------------------------------------

    /**
     * A size with no measure is not a smaller fact than a size with a wrong
     * measure - it is no fact at all, and the unit-price arithmetic would have no
     * divisor. A half-filled form is the ordinary way to produce one.
     */
    @Test
    void anAmountWithoutAUnitIsRefused() {
        GeneralException exception = assertThrows(GeneralException.class,
                () -> policy.normalize(details("500", null)));

        assertEquals("Package amount and package unit must be provided together",
                exception.getMessage());
    }

    @Test
    void aUnitWithoutAnAmountIsRefused() {
        GeneralException exception = assertThrows(GeneralException.class,
                () -> policy.normalize(details(null, "KG")));

        assertEquals("Package amount and package unit must be provided together",
                exception.getMessage());
    }

    // -------------------------------------------------------------------------
    // The amount
    // -------------------------------------------------------------------------

    /**
     * Zero is not "free", it is a division by zero; a negative amount prices a
     * package backwards. Neither may reach the row, because the display path would
     * then have to either hide a real product or show a nonsense number.
     */
    @ParameterizedTest
    @ValueSource(strings = {"0", "0.000", "-1", "-0.5"})
    void anAmountThatIsNotPositiveIsRefused(String amount) {
        GeneralException exception = assertThrows(GeneralException.class,
                () -> policy.normalize(details(amount, "KG")));

        assertEquals("Package amount must be greater than zero", exception.getMessage());
    }

    /**
     * {@code NUMERIC(12, 3)} rounds silently on the values it accepts, so 0.0005
     * would be stored as something the administrator never typed.
     */
    @ParameterizedTest
    @ValueSource(strings = {"0.0001", "1.0001", "12.50001"})
    void anAmountFinerThanThreeDecimalsIsRefused(String amount) {
        GeneralException exception = assertThrows(GeneralException.class,
                () -> policy.normalize(details(amount, "KG")));

        assertEquals("Package amount must not exceed three decimal places",
                exception.getMessage());
    }

    /**
     * Trailing zeros are padding, not precision. A price written as {@code 10.000}
     * is a price, and refusing it would be a rule about spelling.
     */
    @ParameterizedTest
    @ValueSource(strings = {"1", "1.0", "1.000", "500.500", "0.001"})
    void trailingZeroPaddingIsAccepted(String amount) {
        ProductDetails normalized = policy.normalize(details(amount, "KG"));

        assertEquals(0, new BigDecimal(amount).compareTo(normalized.packageAmount()));
    }

    /**
     * Nine integer digits, derived from the column rather than chosen.
     */
    @Test
    void theLargestAmountTheColumnHoldsIsAccepted() {
        ProductDetails normalized = policy.normalize(details("999999999.999", "KG"));

        assertEquals(0, new BigDecimal("999999999.999").compareTo(normalized.packageAmount()));
    }

    @Test
    void anAmountBeyondTheColumnIsRefusedWithTheRange() {
        GeneralException exception = assertThrows(GeneralException.class,
                () -> policy.normalize(details("1000000000.000", "KG")));

        assertEquals("Package amount must not exceed 999999999.999", exception.getMessage());
    }

    // -------------------------------------------------------------------------
    // The unit
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "500, G",
            "1, KG",
            "750, ML",
            "1.5, L",
            "6, ADET"
    })
    void everyAcceptedUnitIsStoredCanonically(String amount, String unit) {
        ProductDetails normalized = policy.normalize(details(amount, unit));

        assertEquals(unit, normalized.packageUnit(),
                "the stored token and the accepted spelling must be one decision");
    }

    /**
     * The admin form offers a closed list, but the API is reachable by anything,
     * so the token is matched leniently here rather than string-compared.
     */
    @ParameterizedTest
    @CsvSource({
            "'1', kg, KG",
            "'1', ' KG ', KG",
            "'1', Kg, KG",
            "'1', adet, ADET",
            "'1', ml, ML"
    })
    void aUnitIsNormalizedBeforeItIsStored(String amount, String unit, String expected) {
        ProductDetails normalized = policy.normalize(details(amount, unit));

        assertEquals(expected, normalized.packageUnit());
    }

    /**
     * A unit nobody defined has no basis to be priced per, so it is refused rather
     * than stored and rendered as a missing number later.
     */
    @ParameterizedTest
    @ValueSource(strings = {"GRAM", "pcs", "KILOGRAM", "1 KG", "undefined", "null"})
    void aUnitOutsideTheClosedSetIsRefusedWithTheAcceptedList(String unit) {
        GeneralException exception = assertThrows(GeneralException.class,
                () -> policy.normalize(details("500", unit)));

        assertEquals("Package unit must be one of [G, KG, ML, L, ADET]",
                exception.getMessage(),
                "the message has to name the options, or the administrator is left guessing");
    }

    // -------------------------------------------------------------------------
    // The one unit-specific amount rule
    // -------------------------------------------------------------------------

    /**
     * A continuous measure may be fractional; a countable one may not, because
     * half an egg is not a thing that can be sold.
     */
    @Test
    void aFractionalItemCountIsRefused() {
        GeneralException exception = assertThrows(GeneralException.class,
                () -> policy.normalize(details("1.5", "ADET")));

        assertEquals("Package amount must be a whole number when the unit is ADET",
                exception.getMessage());
    }

    @Test
    void aWholeItemCountWithTrailingZeroPaddingIsAccepted() {
        ProductDetails normalized = policy.normalize(details("6.000", "ADET"));

        assertEquals("ADET", normalized.packageUnit());
        assertEquals(0, new BigDecimal("6.000").compareTo(normalized.packageAmount()));
    }

    /**
     * The same fraction is fine for every continuous unit, which is what makes the
     * rule about counting rather than about decimals.
     */
    @ParameterizedTest
    @ValueSource(strings = {"G", "KG", "ML", "L"})
    void aFractionalAmountIsFineForAContinuousUnit(String unit) {
        ProductDetails normalized = policy.normalize(details("1.5", unit));

        assertEquals(unit, normalized.packageUnit());
    }

    // -------------------------------------------------------------------------
    // What is written to the row
    // -------------------------------------------------------------------------

    /**
     * The pair is written by the same call that writes the price and the count, so
     * there is no way for a product to be saved with one and not the other.
     */
    @Test
    void aNormalizedPairIsWrittenOntoTheProduct() {
        ProductEntity product = new ProductEntity();
        policy.applyTo(product, policy.normalize(details("1.5", "L")),
                new AdminEntity(), new CategoryEntity());

        assertEquals(0, new BigDecimal("1.5").compareTo(product.getPackageAmount()));
        assertEquals("L", product.getPackageUnit());
    }

    @Test
    void anAbsentPairClearsBothColumnsOnTheProduct() {
        ProductEntity product = new ProductEntity();
        product.setPackageAmount(new BigDecimal("500"));
        product.setPackageUnit("G");

        policy.applyTo(product, policy.normalize(details(null, null)),
                new AdminEntity(), new CategoryEntity());

        assertNull(product.getPackageAmount(),
                "clearing the fields in the admin form has to actually clear the row, which "
                        + "is why the pair is written on every save rather than only when set");
        assertNull(product.getPackageUnit());
    }

    // -------------------------------------------------------------------------
    // What it makes possible downstream
    // -------------------------------------------------------------------------

    /**
     * The point of the whole feature, asserted end to end on the values: a size the
     * policy accepts produces a unit price, and the price it uses is the effective
     * one the card shows.
     */
    @Test
    void aStoredPairYieldsTheUnitPriceTheCustomerComparesBy() {
        ProductDetails normalized = policy.normalize(details("0.5", "KG"));

        BigDecimal effective = ProductPricingPolicy.effectivePrice(
                normalized.productPrice(), normalized.productDiscount());
        ProductUnitPricePolicy.UnitPrice unitPrice =
                ProductUnitPricePolicy.unitPrice(effective, normalized.packageAmount(),
                        normalized.packageUnit());

        assertEquals(0, new BigDecimal("25.00").compareTo(unitPrice.amount()),
                "12.50 for half a kilogram is 25.00 per kilogram");
        assertTrue(unitPrice.basis().name().equals("KG"));
    }
}
