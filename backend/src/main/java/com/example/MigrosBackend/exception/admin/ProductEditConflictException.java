package com.example.MigrosBackend.exception.admin;

/**
 * The admin submitted a product edit against a version the product no longer
 * has.
 *
 * <p>The editor loaded the product at version N, and between that load and the
 * save something else advanced the row: a checkout reserved stock, an order was
 * cancelled or restocked, or another administrator saved. Accepting the edit
 * would write the editor's absolute, now-stale stock count over the current one
 * and hand out stock that is already reserved or sold.
 *
 * <p>Deliberately not a validation error: the request is well formed and the
 * caller did nothing wrong. Recovery requires the editor to reload, review the
 * current values, and save again - never an automatic retry with a freshly
 * fetched version, which would silently reapply the draft over whatever the
 * intervening change was.
 *
 * <p>The message is a fixed, human-readable string. It never carries row values
 * or SQL text.
 */
public class ProductEditConflictException extends RuntimeException {

    public static final String CODE = "PRODUCT_EDIT_CONFLICT";

    public ProductEditConflictException(String message) {
        super(message);
    }

    public static ProductEditConflictException staleVersion() {
        return new ProductEditConflictException(
                "This product was changed by someone else after you opened it. "
                        + "Reload the product, review the current values, and save again.");
    }
}
