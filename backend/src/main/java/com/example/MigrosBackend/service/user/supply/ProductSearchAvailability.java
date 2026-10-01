package com.example.MigrosBackend.service.user.supply;

/**
 * Whether a catalogue search keeps products that cannot currently be bought.
 *
 * <p>{@link #ALL} is the default and it includes sold-out products on purpose.
 * A catalogue that hides everything out of stock cannot answer "is this product
 * still sold here", so a customer who searched for an item and found nothing has
 * no way to tell that the shop simply does not stock it any more. Filtering
 * them out is available, as an explicit choice - it is never the answer to a
 * search that did not ask for it.
 */
public enum ProductSearchAvailability {

    /** In stock and sold out alike. The default. */
    ALL,

    /** Only products with {@code productCount > 0}. */
    IN_STOCK,

    /** Only products with {@code productCount <= 0}. */
    OUT_OF_STOCK
}
