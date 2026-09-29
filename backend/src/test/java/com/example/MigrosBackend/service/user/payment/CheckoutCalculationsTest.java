package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.exception.shared.GeneralException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CheckoutCalculationsTest {

    /**
     * Every product shape a valid stored row can take, as
     * {@code {price, discountPercent}}; a null discount is a row written before
     * the column became {@code NOT NULL} and is still accepted.
     */
    private static final List<String[]> VALID_PRODUCTS = List.of(
            new String[]{"10.00", "0"},
            new String[]{"10.00", "0.00"},
            new String[]{"10.00", null},
            new String[]{"10.00", "12.5"},
            new String[]{"10.01", "12.5"},
            new String[]{"10", "0"},
            new String[]{"10.5", "0.00"},
            new String[]{"0.00", "0"},
            new String[]{"0.00", "100"},
            new String[]{"0.01", "0"},
            new String[]{"0.01", "50"},
            new String[]{"1.00", "0.49"},
            new String[]{"1.00", "0.50"},
            new String[]{"1.00", "0.51"},
            new String[]{"1.00", "100"},
            new String[]{"2.00", "99.99"},
            new String[]{"7.35", "15"},
            new String[]{"19.99", "0.01"},
            new String[]{"12345.67", "1"},
            new String[]{"10.00", "100"},
            new String[]{"10.00", "100.00"},
            new String[]{"1000000000.05", "0"},
            new String[]{"1000000000.05", "50"},
            new String[]{"99999999999999999.99", "0"},
            new String[]{"99999999999999999.99", "10"},
            new String[]{"99999999999999999.99", "100"});

    @Test
    void groupCartQuantitiesSkipsNullIdsAndSortsProductIds() {
        Map<Long, Integer> quantities = CheckoutCalculations.groupCartQuantities(
                Arrays.asList(205L, null, 101L, 205L, 150L, 101L, 205L));

        assertEquals(List.of(101L, 150L, 205L), new ArrayList<>(quantities.keySet()));
        assertEquals(Map.of(101L, 2, 150L, 1, 205L, 3), quantities);
    }

    @Test
    void groupCartQuantitiesReturnsEmptyMapForNullCart() {
        assertEquals(Map.of(), CheckoutCalculations.groupCartQuantities(null));
    }

    @Test
    void effectiveUnitPriceAppliesDiscountAndRoundsToCurrencyScale() {
        ProductEntity product = product("10.01", "12.5");
        BigDecimal unitPrice = CheckoutCalculations.effectiveUnitPrice(product);

        assertEquals(0, new BigDecimal("8.76").compareTo(unitPrice));
    }

    @Test
    void effectiveUnitPriceAllowsNullDiscountAndRejectsInvalidPriceOrDiscount() {
        assertEquals(0, new BigDecimal("10.00").compareTo(CheckoutCalculations.effectiveUnitPrice(
                product("10", null))));
        assertEquals("Product has an invalid price: Apple", assertThrows(GeneralException.class,
                () -> CheckoutCalculations.effectiveUnitPrice(product("10.001", null)))
                .getMessage());
        assertEquals("Product has an invalid discount: Apple", assertThrows(GeneralException.class,
                () -> CheckoutCalculations.effectiveUnitPrice(product("10.00", "100.01")))
                .getMessage());
    }

    /**
     * Pins the extraction of the discount arithmetic into
     * {@code ProductPricingPolicy}: for every valid product the result is
     * byte-identical to the inline implementation that was here before, frozen
     * below exactly as it was.
     *
     * <p>The frozen copy is deliberate. Comparing the new code against a second
     * copy of the new code would only prove it equals itself, and comparing it
     * against literals would not show that the literal and the old code agreed
     * in the first place.
     */
    @Test
    void effectiveUnitPriceMatchesThePreviousInlineImplementationForEveryValidProduct() {
        for (String[] valid : VALID_PRODUCTS) {
            ProductEntity product = product(valid[0], valid[1]);
            assertEquals(inlineEffectiveUnitPriceBeforeExtraction(product),
                    CheckoutCalculations.effectiveUnitPrice(product),
                    "price " + valid[0] + " at " + valid[1] + "% must be unchanged by the extraction");
        }
    }

    /**
     * The customer sees the cart line and is then charged. Both numbers are now
     * produced by one policy, so for a valid product they cannot disagree; this
     * compares the checkout result against the catalog's own pre-extraction
     * expression rather than against the current catalog code, so it is a real
     * cross-caller check and not a tautology.
     */
    @Test
    void everyValidProductIsPricedIdenticallyByTheCartAndByCheckout() {
        for (String[] valid : VALID_PRODUCTS) {
            ProductEntity product = product(valid[0], valid[1]);
            assertEquals(inlineCatalogListingPriceBeforeExtraction(product),
                    CheckoutCalculations.effectiveUnitPrice(product),
                    "price " + valid[0] + " at " + valid[1] + "% must display and charge the same");
        }
    }

    @Test
    void zeroHundredAndOrdinaryPercentDiscountsArePinned() {
        assertEquals(new BigDecimal("10.00"), CheckoutCalculations.effectiveUnitPrice(product("10.00", "0")));
        assertEquals(new BigDecimal("10.00"), CheckoutCalculations.effectiveUnitPrice(product("10", null)));
        assertEquals(new BigDecimal("0.00"), CheckoutCalculations.effectiveUnitPrice(product("10.00", "100")));
        assertEquals(new BigDecimal("0.00"), CheckoutCalculations.effectiveUnitPrice(product("10.00", "100.00")));
        assertEquals(new BigDecimal("8.75"), CheckoutCalculations.effectiveUnitPrice(product("10.00", "12.5")));
        assertEquals(new BigDecimal("0.00"), CheckoutCalculations.effectiveUnitPrice(product("2.00", "99.99")));
    }

    /** Half-up at the third decimal, on both sides of an exact half cent. */
    @Test
    void roundingBoundariesArePinned() {
        assertEquals(new BigDecimal("0.99"), CheckoutCalculations.effectiveUnitPrice(product("1.00", "0.51")));
        assertEquals(new BigDecimal("1.00"), CheckoutCalculations.effectiveUnitPrice(product("1.00", "0.50")));
        assertEquals(new BigDecimal("1.00"), CheckoutCalculations.effectiveUnitPrice(product("1.00", "0.49")));
        assertEquals(new BigDecimal("6.25"), CheckoutCalculations.effectiveUnitPrice(product("7.35", "15")));
        assertEquals(new BigDecimal("12222.21"), CheckoutCalculations.effectiveUnitPrice(product("12345.67", "1")));
    }

    /** The largest value a {@code NUMERIC(19, 2)} money column can hold. */
    @Test
    void largeValidValuesArePinned() {
        assertEquals(new BigDecimal("99999999999999999.99"),
                CheckoutCalculations.effectiveUnitPrice(product("99999999999999999.99", "0")));
        assertEquals(new BigDecimal("89999999999999999.99"),
                CheckoutCalculations.effectiveUnitPrice(product("99999999999999999.99", "10")));
        assertEquals(new BigDecimal("500000000.03"),
                CheckoutCalculations.effectiveUnitPrice(product("1000000000.05", "50")));
    }

    /**
     * Checkout is the sole owner of the charge amount, so every value it cannot
     * charge is refused. None of them may fall through to a price of zero, which
     * would be a charge the customer never agreed to and a total the cart does
     * not show.
     */
    @Test
    void anInvalidPriceIsRejectedRatherThanPricedAsZero() {
        assertEquals("Product has an invalid price: Apple", assertThrows(GeneralException.class,
                () -> CheckoutCalculations.effectiveUnitPrice(product(null, "50"))).getMessage());
        assertEquals("Product has an invalid price: Apple", assertThrows(GeneralException.class,
                () -> CheckoutCalculations.effectiveUnitPrice(product("-0.01", "50"))).getMessage());
        assertEquals("Product has an invalid price: Apple", assertThrows(GeneralException.class,
                () -> CheckoutCalculations.effectiveUnitPrice(product("-100.00", "100"))).getMessage());
        assertEquals("Product has an invalid price: Apple", assertThrows(GeneralException.class,
                () -> CheckoutCalculations.effectiveUnitPrice(product("10.001", "50"))).getMessage());
    }

    @Test
    void anOutOfRangeDiscountIsRejected() {
        assertEquals("Product has an invalid discount: Apple", assertThrows(GeneralException.class,
                () -> CheckoutCalculations.effectiveUnitPrice(product("10.00", "-0.01"))).getMessage());
        assertEquals("Product has an invalid discount: Apple", assertThrows(GeneralException.class,
                () -> CheckoutCalculations.effectiveUnitPrice(product("10.00", "100.01"))).getMessage());
    }

    /**
     * A row that is wrong in both fields is still reported as an invalid price,
     * so the order the two checks run in is pinned as well as their existence.
     */
    @Test
    void thePriceIsValidatedBeforeTheDiscount() {
        assertEquals("Product has an invalid price: Apple", assertThrows(GeneralException.class,
                () -> CheckoutCalculations.effectiveUnitPrice(product("10.001", "150"))).getMessage());
    }

    /**
     * A zero price is a value the money column allows. It is the charge that
     * refuses it, in {@code PaymentAmountConverter}, and this test only records
     * that the unit price still resolves to zero rather than being rejected here.
     */
    @Test
    void aZeroPriceResolvesToZeroAndIsLeftForTheChargeToRefuse() {
        assertEquals(new BigDecimal("0.00"), CheckoutCalculations.effectiveUnitPrice(product("0.00", "0")));
    }

    /**
     * The implementation of {@code effectiveUnitPrice} as it was before the
     * arithmetic moved to {@code ProductPricingPolicy}. Frozen, not refactored.
     */
    private static BigDecimal inlineEffectiveUnitPriceBeforeExtraction(ProductEntity product) {
        BigDecimal price = product.getProductPrice();
        if (price == null || price.signum() < 0 || price.stripTrailingZeros().scale() > 2) {
            throw new GeneralException("Product has an invalid price: " + product.getProductName());
        }
        BigDecimal discount = product.getProductDiscount();
        if (discount == null) {
            discount = BigDecimal.ZERO;
        }
        if (discount.signum() < 0 || discount.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new GeneralException("Product has an invalid discount: " + product.getProductName());
        }

        BigDecimal normalized = price.setScale(2, RoundingMode.HALF_UP);
        if (discount.signum() == 0) {
            return normalized;
        }
        BigDecimal factor = BigDecimal.ONE.subtract(
                discount.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        return normalized.multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * {@code UserCatalogReadService.getEffectivePrice} as it was before the
     * extraction, which is what a product listing displayed and what a cart
     * line was built from.
     */
    private static BigDecimal inlineCatalogListingPriceBeforeExtraction(ProductEntity product) {
        BigDecimal discount = product.getProductDiscount() == null ? BigDecimal.ZERO : product.getProductDiscount();
        BigDecimal price = product.getProductPrice() == null ? BigDecimal.ZERO : product.getProductPrice();
        BigDecimal normalizedPrice = price.setScale(2, RoundingMode.HALF_UP);
        if (discount.signum() <= 0) {
            return normalizedPrice;
        }
        BigDecimal factor = BigDecimal.ONE.subtract(discount.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        return normalizedPrice.multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }

    private ProductEntity product(String price, String discount) {
        ProductEntity product = new ProductEntity();
        product.setProductName("Apple");
        product.setProductPrice(price == null ? null : new BigDecimal(price));
        product.setProductDiscount(discount == null ? null : new BigDecimal(discount));
        return product;
    }
}
