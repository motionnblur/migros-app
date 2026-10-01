import {
  hasPackageSize,
  hasUnitPrice,
  packageSizeLabel,
  unitPriceBasisLabel,
  unitPriceLabel,
} from './product-package-price';

describe('product package price presentation', () => {
  describe('packageSizeLabel', () => {
    it('renders the amount and the unit together', () => {
      expect(packageSizeLabel(1.5, 'L')).toBe('1.5 L');
      expect(packageSizeLabel(500, 'G')).toBe('500 G');
    });

    it('normalizes the unit to its stored casing', () => {
      expect(packageSizeLabel(0.75, 'kg')).toBe('0.75 KG');
    });

    /**
     * A product that predates package metadata has neither field, and that is the
     * overwhelmingly common case. There is nothing to render - not a zero, not a
     * dash - because any of those would read as a package size of nothing.
     */
    it('renders nothing when there is no package size', () => {
      expect(packageSizeLabel(null, null)).toBeNull();
      expect(packageSizeLabel(undefined, undefined)).toBeNull();
      expect(packageSizeLabel(1, null)).toBeNull();
      expect(packageSizeLabel(null, 'L')).toBeNull();
    });

    it('renders nothing for a size with no unit, which the schema forbids', () => {
      expect(packageSizeLabel(500, '')).toBeNull();
      expect(packageSizeLabel(500, '   ')).toBeNull();
    });

    it('renders nothing for an amount that is not a positive number', () => {
      expect(packageSizeLabel(Number.NaN, 'L')).toBeNull();
      expect(packageSizeLabel(Number.POSITIVE_INFINITY, 'L')).toBeNull();
      expect(packageSizeLabel(0, 'L')).toBeNull();
      expect(packageSizeLabel(-1, 'L')).toBeNull();
    });
  });

  describe('hasPackageSize', () => {
    it('requires both halves of the pair', () => {
      expect(hasPackageSize(1, 'L')).toBeTrue();
      expect(hasPackageSize(1, '')).toBeFalse();
      expect(hasPackageSize(null, 'L')).toBeFalse();
    });
  });

  describe('unitPriceBasisLabel', () => {
    it('names each basis in the language the interface is written in', () => {
      expect(unitPriceBasisLabel('KG')).toBe('kg');
      expect(unitPriceBasisLabel('L')).toBe('L');
      expect(unitPriceBasisLabel('ADET')).toBe('adet');
    });

    it('tolerates casing and padding from the server', () => {
      expect(unitPriceBasisLabel(' kg ')).toBe('kg');
      expect(unitPriceBasisLabel('adet')).toBe('adet');
    });

    /**
     * A basis nobody recognises produces no label, which means the caller hides the
     * line. A price printed with no unit beside a package price reads as a second
     * package price - which is the confusion this line exists to remove.
     */
    it('refuses to name a basis it does not know', () => {
      expect(unitPriceBasisLabel('PACKAGE')).toBeNull();
      expect(unitPriceBasisLabel('')).toBeNull();
      expect(unitPriceBasisLabel(null)).toBeNull();
      expect(unitPriceBasisLabel(undefined)).toBeNull();
    });
  });

  describe('unitPriceLabel', () => {
    it('renders the price and the measure it is per', () => {
      expect(unitPriceLabel(33.32, 'KG')).toBe('33.32 TL/kg');
      expect(unitPriceLabel(16.66, 'L')).toBe('16.66 TL/L');
      expect(unitPriceLabel(9.98, 'ADET')).toBe('9.98 TL/adet');
    });

    /**
     * The money scale is two decimals on the server, and the rendering has to match
     * it: `12.5` beside a package price of `12.50 TL` reads as a different price.
     */
    it('always shows two decimals', () => {
      expect(unitPriceLabel(12.5, 'KG')).toBe('12.50 TL/kg');
      expect(unitPriceLabel(12, 'KG')).toBe('12.00 TL/kg');
    });

    it('accepts a caller-supplied formatter', () => {
      expect(unitPriceLabel(12.5, 'KG', (value) => `#${value}`)).toBe('#12.5 TL/kg');
    });

    /**
     * A free product really does have a unit price of zero, and hiding it would
     * make it indistinguishable from a product with no package data - one of which
     * is false.
     */
    it('shows a zero unit price rather than hiding it', () => {
      expect(unitPriceLabel(0, 'KG')).toBe('0.00 TL/kg');
      expect(hasUnitPrice(0, 'KG')).toBeTrue();
    });

    it('renders nothing without a usable price', () => {
      expect(unitPriceLabel(null, 'KG')).toBeNull();
      expect(unitPriceLabel(undefined, 'KG')).toBeNull();
      expect(unitPriceLabel(Number.NaN, 'KG')).toBeNull();
      expect(unitPriceLabel(-1, 'KG')).toBeNull();
    });

    it('renders nothing without a usable basis, even with a price', () => {
      expect(unitPriceLabel(12.5, null)).toBeNull();
      expect(unitPriceLabel(12.5, '')).toBeNull();
      expect(unitPriceLabel(12.5, 'PER_SHELF')).toBeNull();
    });
  });

  describe('hasUnitPrice', () => {
    it('is the single rule both templates condition on', () => {
      expect(hasUnitPrice(12.5, 'KG')).toBeTrue();
      expect(hasUnitPrice(0, 'ADET')).toBeTrue();
      expect(hasUnitPrice(null, 'KG')).toBeFalse();
      expect(hasUnitPrice(12.5, null)).toBeFalse();
      expect(hasUnitPrice(12.5, 'PACKAGE')).toBeFalse();
    });
  });
});
