package com.example.MigrosBackend.entity.user;

/**
 * Order status values shared by the checkout, user, and admin order paths.
 *
 * <p>Only {@link #PENDING} carries a stock rule: an order may be cancelled or
 * deleted only while it is pending, and only then are its reserved units
 * returned to live stock. Admin-supplied status values are stored verbatim, so
 * the existing status strings and API contracts are unchanged.
 */
public final class OrderStatus {

    public static final String PENDING = "Pending";

    private OrderStatus() {
    }

    public static boolean isPending(String status) {
        return PENDING.equalsIgnoreCase(status);
    }
}
