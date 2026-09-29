package com.example.MigrosBackend.repository.user;

import java.math.BigDecimal;

/**
 * One row of the merged admin order listing: either an order group (with the
 * summed price of its lines) or a legacy single-line order.
 *
 * <p>It is a read-only projection so the listing never loads an
 * {@code OrderGroupEntity} collection. The counts and ordering come straight
 * from the database, which is what removes the old all-rows-into-memory path.
 */
public interface AdminOrderRow {
    long getOrderId();

    long getOrderGroupId();

    BigDecimal getTotalPrice();

    String getStatus();
}
