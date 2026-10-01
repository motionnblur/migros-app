import {
  PRODUCT_PACKAGE_UNITS,
  requiresWholePackageAmount,
} from '../interfaces/IProductPackageMetadata';
import {
  PRODUCT_PACKAGE_UNIT_OPTIONS,
  isPackageAmountSet,
  isPackageUnitSet,
  packageAmountStepFor,
  readPackageFields,
  validatePackageFields,
} from './product-package-fields';

describe('admin product package fields', () => {
  describe('the vocabulary', () => {
    it('offers exactly the units the backend accepts', () => {
      expect(PRODUCT_PACKAGE_UNIT_OPTIONS).toEqual([
        'G',
        'KG',
        'ML',
        'L',
        'ADET',
      ]);
      expect(PRODUCT_PACKAGE_UNIT_OPTIONS).toEqual(PRODUCT_PACKAGE_UNITS);
    });
  });

  describe('what counts as "given"', () => {
    /**
     * An admin form hands over `null`, `undefined` and `''` for the same state, so
     * all three have to read as absent. Treating an empty string as "given" would
     * make every untouched form a half-filled pair.
     */
    it('reads every empty spelling as absent', () => {
      expect(isPackageAmountSet(null)).toBeFalse();
      expect(isPackageAmountSet(undefined)).toBeFalse();
      expect(isPackageAmountSet(0)).toBeTrue();
      expect(isPackageAmountSet(1.5)).toBeTrue();

      expect(isPackageUnitSet(null)).toBeFalse();
      expect(isPackageUnitSet(undefined)).toBeFalse();
      expect(isPackageUnitSet('')).toBeFalse();
      expect(isPackageUnitSet('   ')).toBeFalse();
      expect(isPackageUnitSet('KG')).toBeTrue();
    });
  });

  describe('readPackageFields', () => {
    it('reads a filled pair as the pair the API expects', () => {
      expect(readPackageFields(1.5, 'L')).toEqual({
        packageAmount: 1.5,
        packageUnit: 'L',
      });
    });

    it('normalizes the unit casing before submitting it', () => {
      expect(readPackageFields(0.75, 'kg')).toEqual({
        packageAmount: 0.75,
        packageUnit: 'KG',
      });
    });

    /**
     * Both absent is the ordinary result for a product with no package size, and
     * it has to survive as an explicit "no size" rather than collapsing into a
     * partial pair.
     */
    it('reports both as null when nothing was entered', () => {
      expect(readPackageFields(null, '')).toEqual({
        packageAmount: null,
        packageUnit: null,
      });
      expect(readPackageFields(undefined, undefined)).toEqual({
        packageAmount: null,
        packageUnit: null,
      });
    });

    /**
     * A half-filled form is refused by `validatePackageFields` before it is ever
     * read; this is the last-resort reading so that a caller which skipped the
     * validation still sends "no size" rather than "a size in no measure".
     */
    it('collapses a half-filled pair to nothing rather than sending half of it', () => {
      expect(readPackageFields(500, '')).toEqual({
        packageAmount: null,
        packageUnit: null,
      });
      expect(readPackageFields(null, 'KG')).toEqual({
        packageAmount: null,
        packageUnit: null,
      });
    });
  });

  describe('packageAmountStepFor', () => {
    /**
     * `ADET` counts discrete items, so the browser is never offered a fraction.
     * The backend refuses the value too; this only means the control does not
     * propose it.
     */
    it('offers whole numbers only for the item unit', () => {
      expect(packageAmountStepFor('ADET')).toBe(1);
      expect(requiresWholePackageAmount('adet')).toBeTrue();
    });

    it('offers the column scale for every continuous measure', () => {
      expect(packageAmountStepFor('G')).toBe(0.001);
      expect(packageAmountStepFor('KG')).toBe(0.001);
      expect(packageAmountStepFor('ML')).toBe(0.001);
      expect(packageAmountStepFor('L')).toBe(0.001);
      expect(packageAmountStepFor('')).toBe(0.001);
      expect(requiresWholePackageAmount('L')).toBeFalse();
    });
  });

  describe('validatePackageFields', () => {
    it('accepts an untouched form', () => {
      expect(validatePackageFields(null, '')).toBeNull();
      expect(validatePackageFields(undefined, undefined)).toBeNull();
    });

    it('accepts a complete, plausible pair', () => {
      expect(validatePackageFields(1.5, 'L')).toBeNull();
      expect(validatePackageFields(0.001, 'KG')).toBeNull();
      expect(validatePackageFields(999999999.999, 'G')).toBeNull();
      expect(validatePackageFields(6, 'ADET')).toBeNull();
    });

    /**
     * The ordinary way to produce a half-filled pair: filling in the amount and
     * forgetting the unit. Without this the backend refuses the save with a
     * message, which is worse than being told before the request.
     */
    it('refuses an amount with no unit, and says they go together', () => {
      expect(validatePackageFields(500, '')).toBe(
        'Package amount and package unit are both optional, but they go together.',
      );
    });

    it('refuses a unit with no amount', () => {
      expect(validatePackageFields(null, 'KG')).toBe(
        'Package amount and package unit are both optional, but they go together.',
      );
    });

    /**
     * Zero is a division by zero rather than a free package, and a negative amount
     * would price the product backwards.
     */
    it('refuses an amount that is not positive', () => {
      expect(validatePackageFields(0, 'KG')).toBe(
        'Package amount must be greater than zero.',
      );
      expect(validatePackageFields(-1, 'KG')).toBe(
        'Package amount must be greater than zero.',
      );
      expect(validatePackageFields(Number.NaN, 'KG')).toBe(
        'Package amount must be greater than zero.',
      );
    });

    it('refuses a fractional item count and allows a fractional measure', () => {
      expect(validatePackageFields(1.5, 'ADET')).toBe(
        'Package amount must be a whole number when the unit is ADET.',
      );
      expect(validatePackageFields(1.5, 'L')).toBeNull();
      expect(validatePackageFields(0.5, 'KG')).toBeNull();
    });
  });
});
