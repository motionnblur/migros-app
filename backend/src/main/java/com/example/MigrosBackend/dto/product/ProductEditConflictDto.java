package com.example.MigrosBackend.dto.product;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Stable typed contract for a rejected product edit (HTTP 409).
 *
 * <p>Clients branch on {@code code} and never on message text.
 *
 * <p>Deliberately does <em>not</em> publish the product's current version. A
 * conflict response that handed back the fresh version would let a client
 * resubmit the very draft that was just rejected, which is precisely the
 * silent overwrite this response exists to prevent. Recovery goes through the
 * normal product-detail read so the editor sees the current values and reviews
 * them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductEditConflictDto(
        String code,
        String message,
        int status) {
}
