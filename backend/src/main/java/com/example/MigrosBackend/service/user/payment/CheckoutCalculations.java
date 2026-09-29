package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.exception.shared.GeneralException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure cart and price calculations used while preparing a checkout. */
final class CheckoutCalculations {

    static final int MONEY_SCALE = 2;

    private CheckoutCalculations() {
    }

    static Map<Long, Integer> groupCartQuantities(List<Long> cart) {
        Map<Long, Integer> counts = new TreeMap<>();
        if (cart == null) {
            return counts;
        }
        for (Long productId : cart) {
            if (productId != null) {
                counts.merge(productId, 1, Integer::sum);
            }
        }
        return counts;
    }

    static BigDecimal effectiveUnitPrice(ProductEntity product) {
        BigDecimal price = product.getProductPrice();
        if (price == null || price.signum() < 0 || price.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw new GeneralException("Product has an invalid price: " + product.getProductName());
        }
        BigDecimal discount = product.getProductDiscount();
        if (discount == null) {
            discount = BigDecimal.ZERO;
        }
        if (discount.signum() < 0 || discount.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new GeneralException("Product has an invalid discount: " + product.getProductName());
        }

        BigDecimal normalized = price.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (discount.signum() == 0) {
            return normalized;
        }
        BigDecimal factor = BigDecimal.ONE.subtract(
                discount.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        return normalized.multiply(factor).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
