import { IUserCartItemDto } from '../../../interfaces/IUserCartItemDto';

export type CartQuantityAction = 'increase' | 'decrease';

export type CartQuantityChange =
  | { kind: 'missing' }
  | { kind: 'stock-limit'; availableStock: number }
  | { kind: 'remove' }
  | { kind: 'update'; quantity: number };

export function calculateCartTotal(items: readonly IUserCartItemDto[]): number {
  return items.reduce(
    (total, item) => total + item.productPrice * item.productCount,
    0,
  );
}

/**
 * Whether two rendered carts are the same cart.
 *
 * <p>Compared order-insensitively, because the cart itself is an unordered
 * multiset of product ids: writing a new count moves its product to the end of
 * the stored list, and the server renders both the plain read and the
 * reconciliation by id. A differing order is therefore not a difference at all,
 * and treating it as one would make a healthy cart demand a confirmation the
 * customer never earned.
 *
 * <p>Everything the customer can see for a line is compared, not just the
 * quantity, so a price or stock change is treated as the change it is. The cost
 * of being strict here is one extra press; the cost of being lax is confirming a
 * cart that no longer matches what will be reserved.
 */
export function isSameCartContent(
  left: readonly IUserCartItemDto[],
  right: readonly IUserCartItemDto[],
): boolean {
  if (left.length !== right.length) {
    return false;
  }
  const byProductId = new Map<number, IUserCartItemDto>();
  left.forEach((item) => byProductId.set(item.productId, item));
  return right.every((item) => {
    const counterpart = byProductId.get(item.productId);
    return (
      counterpart !== undefined &&
      counterpart.productCount === item.productCount &&
      counterpart.productPrice === item.productPrice &&
      counterpart.availableStock === item.availableStock
    );
  });
}

/** Resolve the next cart quantity without mutating the cart item. */
export function resolveCartQuantityChange(
  item: IUserCartItemDto | undefined,
  action: CartQuantityAction,
): CartQuantityChange {
  if (!item) {
    return { kind: 'missing' };
  }

  if (action === 'increase') {
    if (item.productCount >= item.availableStock) {
      return { kind: 'stock-limit', availableStock: item.availableStock };
    }
    return { kind: 'update', quantity: item.productCount + 1 };
  }

  if (item.productCount <= 1) {
    return { kind: 'remove' };
  }

  return { kind: 'update', quantity: item.productCount - 1 };
}
