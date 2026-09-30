import { IUserCartItemDto } from './IUserCartItemDto';

/**
 * The result of an explicit cart reconciliation.
 *
 * <p>`cart` is the reconciled cart, rendered exactly as a plain cart read
 * renders it, so the client can adopt it directly instead of issuing a second
 * read that could observe a yet newer state.
 *
 * <p>The two id lists are reported separately because they mean different things
 * to a customer and collapsing them would either hide a line they expected to
 * buy or invent an error for one that was merely reduced:
 *
 * <ul>
 *   <li>`removedProductIds` - deleted or sold out, so unbuyable at any quantity
 *       and dropped from the cart.</li>
 *   <li>`reducedProductIds` - still available, but the stored quantity exceeded
 *       the remaining stock and was lowered to it.</li>
 * </ul>
 */
export interface ICartReconciliation {
  cart: IUserCartItemDto[];
  removedProductIds: number[];
  reducedProductIds: number[];
}
