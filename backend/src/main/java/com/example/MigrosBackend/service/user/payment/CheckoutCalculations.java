package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.helper.ProductPricingPolicy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure cart and price calculations used while preparing a checkout. */
final class CheckoutCalculations {

    static final int MONEY_SCALE = ProductPricingPolicy.MONEY_SCALE;

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

    /**
     * The payable unit price, which the checkout is the sole owner of.
     *
     * <p>The discount arithmetic lives in {@link ProductPricingPolicy} so this
     * total is the same number the cart displayed; only the strictness is local.
     * Both fields are validated before anything is priced, and the price check
     * comes first, so a row that is wrong in both fields is still reported as an
     * invalid price exactly as before. An invalid value is rejected rather than
     * priced as zero, because a silent zero here becomes a charge that does not
     * match the cart.
     */
    static BigDecimal effectiveUnitPrice(ProductEntity product) {
        BigDecimal price = ProductPricingPolicy.requireValidPrice(
                product.getProductPrice(), product.getProductName());
        BigDecimal discount = ProductPricingPolicy.requireValidDiscount(
                product.getProductDiscount(), product.getProductName());
        return ProductPricingPolicy.effectivePrice(price, discount);
    }
}
