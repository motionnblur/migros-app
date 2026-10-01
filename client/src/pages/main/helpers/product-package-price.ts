import { ProductUnitPriceBasis } from '../../../interfaces/IProductPackageMetadata';
import { formatAmount, formatQuantity } from './money-format';

/**
 * How package size and unit price are rendered on customer-facing surfaces.
 *
 * Everything here is presentation. The numbers themselves - the package amount,
 * the unit, the unit price and the basis it is per - are computed by the backend
 * and arrive already rounded, because a unit price recomputed in the browser is a
 * second copy of `ProductUnitPricePolicy` and a second thing that can disagree
 * with the package price printed next to it.
 *
 * The *notation* is `money-format`'s, so a card reads `0,75 KG` over `66,65 TL/kg`
 * over `49,90 TL` rather than mixing separators. It is not passed in as a
 * formatter argument: a parameter here is a second way to render the same money,
 * and the two callers that used it both passed `toFixed(2)`, which is how this
 * module ended up printing `66.65 TL/kg` beside a cart total of `1.234,50 TL`.
 *
 * The one rule every function here follows is that an absent fact renders as
 * nothing. A product without a package size - which is every product that predates
 * package metadata - shows no package line and no unit price, rather than a zero,
 * a dash, or a quantity inferred from the product name.
 */

/** The label for the package size line, or `null` when there is no package size. */
export function packageSizeLabel(
  packageAmount: number | null | undefined,
  packageUnit: string | null | undefined,
): string | null {
  if (!hasPackageSize(packageAmount, packageUnit)) {
    return null;
  }
  return `${formatQuantity(packageAmount as number)} ${String(packageUnit).trim().toUpperCase()}`;
}

/**
 * Whether a product carries a package size that can be shown.
 *
 * Both halves must be present, which is also the invariant the backend enforces.
 * Checking only the amount would render "500 " with a dangling unit on a row that
 * somehow held half a pair.
 */
export function hasPackageSize(
  packageAmount: number | null | undefined,
  packageUnit: string | null | undefined,
): boolean {
  return (
    typeof packageAmount === 'number' &&
    Number.isFinite(packageAmount) &&
    packageAmount > 0 &&
    typeof packageUnit === 'string' &&
    packageUnit.trim() !== ''
  );
}

/**
 * The "per" label for a unit price.
 *
 * `null` for a basis outside the three the server names, which means the caller
 * hides the unit-price line rather than printing a price with no unit: an
 * unqualified "12,50 TL" next to a package price reads as the package price
 * again, which is the confusion this line exists to remove.
 */
export function unitPriceBasisLabel(basis: string | null | undefined): string | null {
  switch ((basis ?? '').trim().toUpperCase()) {
    case 'KG':
      return 'kg';
    case 'L':
      return 'L';
    case 'ADET':
      return 'adet';
    default:
      return null;
  }
}

/**
 * Whether a unit price can be shown at all: a finite, nonnegative amount and a
 * basis the client recognises.
 *
 * A zero unit price is shown - a genuinely free product has a unit price of zero,
 * and hiding it would misreport the product rather than protect the customer.
 * What cannot be shown is a missing, negative or non-numeric value, and a basis
 * that names nothing.
 */
export function hasUnitPrice(
  unitPrice: number | null | undefined,
  basis: string | null | undefined,
): boolean {
  return (
    typeof unitPrice === 'number' &&
    Number.isFinite(unitPrice) &&
    unitPrice >= 0 &&
    unitPriceBasisLabel(basis) !== null
  );
}

/**
 * The unit-price line as one string, or `null` when it must not be rendered.
 *
 * The number goes through the storefront's own money formatter rather than being
 * concatenated raw, so a unit price of 12.5 does not appear as "12,5 TL/kg" beside
 * a package price of "12,50 TL" - and so it cannot appear as "12.50 TL/kg" beside
 * a cart total of "12,50 TL".
 */
export function unitPriceLabel(
  unitPrice: number | null | undefined,
  basis: string | null | undefined,
): string | null {
  if (!hasUnitPrice(unitPrice, basis)) {
    return null;
  }

  return `${formatAmount(unitPrice as number)} TL/${unitPriceBasisLabel(basis)}`;
}

/** The typed basis union, re-exported so templates do not import the interface. */
export type { ProductUnitPriceBasis };
