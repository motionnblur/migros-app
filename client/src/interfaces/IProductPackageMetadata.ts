/**
 * What a unit price is measured against - the "per" in "price per kg".
 *
 * Mirrors `ProductPriceBasis` on the backend, which is the single owner of the
 * mapping from a stored package unit to the measure a price is compared in.
 * `G` and `KG` both arrive here as `'KG'`; `ML` and `L` both as `'L'`.
 *
 * The server sends this rather than the client deriving it from
 * `packageUnit`, because that mapping is a decision (500 G and 0.5 KG are the
 * same package) and a second copy of it in the browser is a second thing that can
 * disagree with the number already computed next to it.
 */
export type ProductUnitPriceBasis = 'KG' | 'L' | 'ADET';

/**
 * The measures a package amount may be counted in.
 *
 * The same closed set the backend accepts, in the order the admin form offers
 * it. A free-text field here would let an administrator submit a unit the server
 * refuses, turning a typo into a failed save with a message about the unit
 * instead of about what was meant.
 */
export const PRODUCT_PACKAGE_UNITS = ['G', 'KG', 'ML', 'L', 'ADET'] as const;

export type ProductPackageUnit = (typeof PRODUCT_PACKAGE_UNITS)[number];

/**
 * Whether a package amount may be entered for a given unit.
 *
 * `ADET` counts discrete items, so half an egg is not a thing that can be sold.
 * The backend enforces the same rule; repeating it here means the form can say so
 * before the save is attempted, and it is stated in one place so the message and
 * the disabled state cannot disagree.
 */
export function requiresWholePackageAmount(unit: string | null | undefined): boolean {
  return (unit ?? '').trim().toUpperCase() === 'ADET';
}

/**
 * A package amount together with its unit, or nothing.
 *
 * `null` for both fields together is the normal state for a product that predates
 * package metadata, and it is deliberately not modelled as a default of
 * `{ amount: 0, unit: 'G' }`: that would put a quantity nobody entered into the
 * request and give the product a package size of zero grams.
 */
export interface IProductPackageMetadata {
  packageAmount: number | null;
  packageUnit: ProductPackageUnit | null;
}
