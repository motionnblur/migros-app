package com.example.MigrosBackend.dto.user.product;

import java.util.List;

/**
 * What a cart reconciliation changed, and the cart as it now stands.
 *
 * <p>The two id lists are reported separately because they mean different things
 * to a customer, and collapsing them would either hide a line they expected to
 * buy or invent an error for one that was merely reduced:
 *
 * <ul>
 *   <li>{@code removedProductIds} - the product was deleted or sold out, so it
 *       cannot be bought at any quantity and was dropped.</li>
 *   <li>{@code reducedProductIds} - the product is still available, but the
 *       stored quantity exceeded the remaining stock and was lowered to it.</li>
 * </ul>
 *
 * <p>{@code cart} is rendered exactly as a plain cart read renders it, from the
 * reconciled stored list, so the client can replace its view with this response
 * rather than issuing a second read that could observe a yet newer state.
 *
 * @param cart               the reconciled cart, in the same shape the cart read returns
 * @param removedProductIds  products dropped entirely, sorted for a stable response
 * @param reducedProductIds  products whose quantity was lowered, sorted for a stable response
 */
public record CartReconciliationDto(List<UserCartItemDto> cart,
                                    List<Long> removedProductIds,
                                    List<Long> reducedProductIds) {
}
