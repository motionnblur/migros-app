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
