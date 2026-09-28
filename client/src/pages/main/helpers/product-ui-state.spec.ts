import { ObjectUrlManager } from './object-url-manager';
import { productCartErrorMessage } from './product-cart-error';

describe('product UI state helpers', () => {
  it('releases the previous object URL before replacing it and on teardown', () => {
    const createSpy = spyOn(URL, 'createObjectURL').and.returnValues(
      'blob:first',
      'blob:second',
    );
    const revokeSpy = spyOn(URL, 'revokeObjectURL');
    const manager = new ObjectUrlManager();

    expect(manager.create(new Blob(['first']))).toBe('blob:first');
    expect(manager.create(new Blob(['second']))).toBe('blob:second');
    manager.release();
    manager.release();

    expect(createSpy).toHaveBeenCalledTimes(2);
    expect(revokeSpy.calls.allArgs()).toEqual([
      ['blob:first'],
      ['blob:second'],
    ]);
  });

  it('uses trimmed backend messages and falls back for empty or non-text errors', () => {
    expect(productCartErrorMessage({ error: '  Stock changed.  ' })).toBe(
      'Stock changed.',
    );
    expect(productCartErrorMessage({ error: '  ' })).toBe(
      'Ürün sepete eklenemedi.',
    );
    expect(productCartErrorMessage({ error: { message: 'ignored' } }, 'Fallback')).toBe(
      'Fallback',
    );
    expect(productCartErrorMessage(null, 'Fallback')).toBe('Fallback');
  });
});
