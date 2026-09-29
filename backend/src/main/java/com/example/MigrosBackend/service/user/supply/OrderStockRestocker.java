package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * Returns reserved units from a cancelled or deleted order to live stock.
 *
 * <p>Two properties matter and are both enforced here rather than at each call
 * site:
 *
 * <ul>
 *   <li><b>Atomic increment.</b> Stock is raised with a single
 *       {@code productCount = productCount + :quantity} statement instead of a
 *       read-modify-write on a managed entity. A read-modify-write loses
 *       concurrent updates: two cancellations restocking the same product both
 *       read the same starting value and the second overwrites the first, so
 *       units silently vanish from stock.</li>
 *   <li><b>One lock order.</b> Quantities are aggregated per product and
 *       applied in ascending product-id order. Two overlapping carts or orders
 *       therefore always acquire the same product-row locks in the same
 *       sequence, so concurrent restocks cannot deadlock.</li>
 * </ul>
 *
 * <p>Callers must already hold the order row lock and run inside a
 * transaction; restocking is only correct when the surrounding transaction can
 * roll the order deletion back together with the stock change.
 */
@Component
public class OrderStockRestocker {

    private final ProductEntityRepository productEntityRepository;

    public OrderStockRestocker(ProductEntityRepository productEntityRepository) {
        this.productEntityRepository = productEntityRepository;
    }

    /** Restocks a whole order (or the items of one order group). */
    public void restore(Collection<OrderEntity> orderItems) {
        if (orderItems == null || orderItems.isEmpty()) {
            return;
        }
        // TreeMap: ascending product id, so lock acquisition order is stable.
        Map<Long, Integer> quantityByProduct = new TreeMap<>();
        for (OrderEntity orderItem : orderItems) {
            if (orderItem == null || orderItem.getItemId() == null) {
                continue;
            }
            int quantity = orderItem.getCount() == null ? 0 : orderItem.getCount();
            if (quantity <= 0) {
                continue;
            }
            quantityByProduct.merge(orderItem.getItemId(), quantity, Integer::sum);
        }
        applyRestock(quantityByProduct);
    }

    /** Restocks a single line, for legacy orders that have no group. */
    public void restore(Long productId, int quantity) {
        if (productId == null || quantity <= 0) {
            return;
        }
        Map<Long, Integer> quantityByProduct = new TreeMap<>();
        quantityByProduct.put(productId, quantity);
        applyRestock(quantityByProduct);
    }

    private void applyRestock(Map<Long, Integer> quantityByProduct) {
        for (Map.Entry<Long, Integer> entry : quantityByProduct.entrySet()) {
            productEntityRepository.incrementStock(entry.getKey(), entry.getValue());
        }
    }
}
