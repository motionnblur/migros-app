package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.exception.shared.GeneralException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CheckoutCalculationsTest {

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

    private ProductEntity product(String price, String discount) {
        ProductEntity product = new ProductEntity();
        product.setProductName("Apple");
        product.setProductPrice(price == null ? null : new BigDecimal(price));
        product.setProductDiscount(discount == null ? null : new BigDecimal(discount));
        return product;
    }
}
