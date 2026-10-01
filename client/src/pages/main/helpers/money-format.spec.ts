import {
  STOREFRONT_LOCALE,
  formatAmount,
  formatCurrencyLabel,
  formatMoney,
  formatQuantity,
} from './money-format';

/**
 * The storefront's number rendering.
 *
 * <p>These are the assertions that keep one amount looking like one amount
 * everywhere. The defect they exist for was not a wrong number - it was the same
 * number written two ways: `30.00 TL` on a product card and `30,00 TL` in the
 * cart. So the assertions are about separators and about the locale being named
 * rather than inherited, because an inherited locale is how the two renderings
 * happened in the first place.
 */
describe('storefront money format', () => {
  describe('the locale', () => {
    /**
     * Not a cosmetic assertion. `toFixed` and Angular's `number` pipe both follow
     * whatever the browser reports, so the storefront used to print `30,00` on a
     * Turkish device and `30.00` on every other one - the same product, two
     * renderings, decided by the customer's OS rather than by the product.
     */
    it('is named rather than inherited from the browser', () => {
      expect(STOREFRONT_LOCALE).toBe('tr-TR');
    });

    it('is not the notation the defect was reported in', () => {
      const enUs = new Intl.NumberFormat('en-US', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      });

      expect(enUs.format(30)).toBe('30.00');
      expect(formatAmount(30)).toBe('30,00');
    });
  });

  describe('formatAmount', () => {
    it('uses the Turkish separators', () => {
      expect(formatAmount(30)).toBe('30,00');
      expect(formatAmount(1234.5)).toBe('1.234,50');
      expect(formatAmount(1234567.891)).toBe('1.234.567,89');
    });

    /**
     * The backend's money scale is `NUMERIC(19,2)`, so a third decimal would be
     * precision the number does not have and a missing one would read as a
     * different amount than the charge.
     */
    it('holds the money scale at two decimals', () => {
      expect(formatAmount(0)).toBe('0,00');
      expect(formatAmount(12.5)).toBe('12,50');
      expect(formatAmount(9.999)).toBe('10,00');
    });

    it('renders a value the API sent as a string without changing it', () => {
      expect(formatAmount('30')).toBe('30,00');
      expect(formatAmount('1234.50')).toBe('1.234,50');
    });

    /**
     * An unparseable amount is a display bug, not a value to invent. The neutral
     * form is shown so the layout holds, and the surrounding status line is left
     * to explain the real state.
     */
    it('never throws on something it cannot read', () => {
      expect(formatAmount('not a number')).toBe('0,00');
      expect(formatAmount(undefined)).toBe('0,00');
      expect(formatAmount(null)).toBe('0,00');
      expect(formatAmount(Number.NaN)).toBe('0,00');
    });
  });

  describe('formatQuantity', () => {
    /**
     * A package amount is a measure, not money: rounding `0,125 KG` to `0,13 KG`
     * would misreport what the customer is buying. Three decimals is the scale the
     * column can carry.
     */
    it('keeps the precision a package amount can carry', () => {
      expect(formatQuantity(0.125)).toBe('0,125');
      expect(formatQuantity(0.75)).toBe('0,75');
    });

    it('trims the trailing zeros a measure does not need', () => {
      expect(formatQuantity(1)).toBe('1');
      expect(formatQuantity(1.5)).toBe('1,5');
      expect(formatQuantity(500)).toBe('500');
    });

    it('is the same notation as the price beside it', () => {
      expect(formatQuantity(1.5)).not.toContain('.');
      expect(formatAmount(1.5)).not.toContain('.');
    });
  });

  describe('the currency', () => {
    it('is a label derived from the code the API sent, never a chosen one', () => {
      expect(formatCurrencyLabel('try')).toBe('TL');
      expect(formatCurrencyLabel('TRY')).toBe('TL');
      expect(formatCurrencyLabel('EUR')).toBe('EUR');
    });

    it('falls back to the one currency the backend settles in', () => {
      expect(formatCurrencyLabel(undefined)).toBe('TL');
      expect(formatCurrencyLabel('')).toBe('TL');
    });
  });

  describe('formatMoney', () => {
    it('joins the amount and the currency as one string', () => {
      expect(formatMoney(1234.5, 'TRY')).toBe('1.234,50 TL');
    });
  });
});
