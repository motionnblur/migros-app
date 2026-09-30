import { IUserCartItemDto } from '../../../interfaces/IUserCartItemDto';
import {
  calculateCartTotal,
  isSameCartContent,
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

  it('treats a reordered cart as the same cart', () => {
    // The cart is an unordered multiset of ids: writing a new count moves its
    // product to the end of the stored list, so order carries no meaning and
    // must not cost the customer a confirmation.
    const other: IUserCartItemDto = {
      ...item,
      productId: 6,
      productName: 'Yoğurt',
      productPrice: 4,
      productCount: 1,
    };
    expect(isSameCartContent([item, other], [other, item])).toBeTrue();
  });

  it('detects every difference the customer would see', () => {
    const other: IUserCartItemDto = {
      ...item,
      productId: 6,
      productName: 'Yoğurt',
      productPrice: 4,
      productCount: 1,
    };
    expect(isSameCartContent([item, other], [item])).toBeFalse();
    expect(isSameCartContent([item], [])).toBeFalse();
    expect(isSameCartContent([], [])).toBeTrue();
    expect(
      isSameCartContent([item], [{ ...item, productCount: 3 }]),
    ).toBeFalse();
    // A price or stock change is a change to what the line means, not a
    // rendering detail.
    expect(
      isSameCartContent([item], [{ ...item, productPrice: 13 }]),
    ).toBeFalse();
    expect(
      isSameCartContent([item], [{ ...item, availableStock: 2 }]),
    ).toBeFalse();
  });
});
