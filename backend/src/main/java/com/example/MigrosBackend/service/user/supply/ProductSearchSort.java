package com.example.MigrosBackend.service.user.supply;

/**
 * How a catalogue search orders its results.
 *
 * <p>Every mode ends in the product id, so the ordering is total and the page
 * windows are disjoint. See {@code ProductSearchSpecifications.ordersOf} for why
 * that is not optional.
 */
public enum ProductSearchSort {

    /**
     * In stock first, then product id ascending.
     *
     * <p>The default is the only ordering that answers the question a plain
     * catalogue browse is asking - what can I buy - without pretending to be a
     * price sort the caller did not request. Product id ascending is the tie
     * break rather than anything else because it is the one order that was
     * already in use for the catalogue listing, so an unfiltered search and the
     * existing listings disagree with each other as little as possible.
     */
    DEFAULT,

    /** Cheapest effective price first. */
    PRICE_ASC,

    /** Most expensive effective price first. */
    PRICE_DESC
}
