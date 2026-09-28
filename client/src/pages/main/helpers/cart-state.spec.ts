import { IUserCartItemDto } from '../../../interfaces/IUserCartItemDto';
import {
  calculateCartTotal,
  resolveCartQuantityChange,
} from './cart-state';

describe('cart state helpers', () => {
  const item: IUserCartItemDto = {
    productId: 5,
    productName: 'Süt',
    productPrice: 12.5,
    productCount: 2,
    availableStock: 3,
  };

  it('calculates the displayed total from the current cart quantities', () => {
    expect(
      calculateCartTotal([
        item,
        { ...item, productId: 6, productPrice: 4, productCount: 3 },
      ]),
    ).toBe(37);
    expect(calculateCartTotal([])).toBe(0);
  });

  it('resolves quantity changes without mutating the cart item', () => {
    expect(resolveCartQuantityChange(item, 'increase')).toEqual({
      kind: 'update',
      quantity: 3,
    });
    expect(resolveCartQuantityChange(item, 'decrease')).toEqual({
      kind: 'update',
      quantity: 1,
    });
    expect(item.productCount).toBe(2);
  });

  it('signals stock limits, last-unit removal, and missing items explicitly', () => {
    expect(
      resolveCartQuantityChange({ ...item, productCount: 3 }, 'increase'),
    ).toEqual({ kind: 'stock-limit', availableStock: 3 });
    expect(
      resolveCartQuantityChange({ ...item, productCount: 1 }, 'decrease'),
    ).toEqual({ kind: 'remove' });
    expect(resolveCartQuantityChange(undefined, 'increase')).toEqual({
      kind: 'missing',
    });
  });
});
