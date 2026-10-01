import {
  IProductPackageMetadata,
  PRODUCT_PACKAGE_UNITS,
  ProductPackageUnit,
  requiresWholePackageAmount,
} from '../interfaces/IProductPackageMetadata';

/**
 * The rules for the two optional package fields, shared by every admin product
 * form.
 *
 * The creation form, the update drawer and the modal editor are three separate
 * components with three separate templates, and each of them writes a product.
 * Three hand-written copies of "a size without a unit is not a size" is how one of
 * them ends up accepting a half-filled pair while the other two reject it, and the
 * symptom is a save that fails with a message the other forms never show.
 *
 * The backend remains the authority - `ProductCreationPolicy` applies these rules
 * on every path, including clients that are not this one - but a form that can say
 * what is wrong while the administrator is still looking at the field is worth
 * more than one that reports it after a round trip.
 *
 * Pure functions on purpose: they take the two field values and return a result,
 * so a form can be tested without a component fixture and a component has no
 * decision of its own to get wrong.
 */
export type ProductPackageFields = IProductPackageMetadata;

/** The unit options a package selector offers, in order. */
export const PRODUCT_PACKAGE_UNIT_OPTIONS: readonly ProductPackageUnit[] =
  PRODUCT_PACKAGE_UNITS;

/**
 * Whether a unit selector has a choice at all.
 *
 * An empty string is the "no unit" option rather than a member of the unit set,
 * which is why it is a field value rather than a `null`: the select needs a real
 * option to bind to, and `null` would make "not chosen" and "not applicable"
 * indistinguishable in the DOM.
 */
export function isPackageUnitSet(unit: string | null | undefined): boolean {
  return (unit ?? '').trim() !== '';
}

/** Whether an amount has been entered, treating an empty input as absent. */
export function isPackageAmountSet(amount: number | null | undefined): boolean {
  if (amount === null || amount === undefined) {
    return false;
  }
  return String(amount).trim() !== '';
}

/**
 * The values to submit for the two fields.
 *
 * Both absent is the ordinary result for a product with no package size and is
 * sent as such, rather than being omitted: omitting them would make "cleared" and
 * "unchanged" indistinguishable to the server, and clearing the fields is how
 * package metadata is removed.
 */
export function readPackageFields(
  amount: number | null | undefined,
  unit: string | null | undefined,
): ProductPackageFields {
  if (!isPackageAmountSet(amount) || !isPackageUnitSet(unit)) {
    return { packageAmount: null, packageUnit: null };
  }

  return {
    packageAmount: Number(String(amount).trim()),
    packageUnit: String(unit).trim().toUpperCase() as ProductPackageUnit,
  };
}

/**
 * The step the amount input offers for the chosen unit.
 *
 * `ADET` counts discrete items, so the browser itself is not offered `1.5` for
 * it. That is a courtesy, not the enforcement - the backend refuses the value and
 * `validatePackageFields` says so before the save is attempted.
 */
export function packageAmountStepFor(unit: string | null | undefined): number {
  return requiresWholePackageAmount(unit) ? 1 : 0.001;
}

/**
 * What is wrong with the pair, or `null` when it is fine.
 *
 * Returned as a message rather than thrown or returned as a boolean so the form
 * can show the same sentence in its own error slot, and so the rules have exactly
 * one wording.
 */
export function validatePackageFields(
  amount: number | null | undefined,
  unit: string | null | undefined,
): string | null {
  const amountGiven = isPackageAmountSet(amount);
  const unitGiven = isPackageUnitSet(unit);

  if (amountGiven !== unitGiven) {
    return 'Package amount and package unit are both optional, but they go together.';
  }

  if (!amountGiven) {
    return null;
  }

  const value = Number(String(amount).trim());
  if (!Number.isFinite(value) || value <= 0) {
    return 'Package amount must be greater than zero.';
  }

  if (requiresWholePackageAmount(unit) && !Number.isInteger(value)) {
    return 'Package amount must be a whole number when the unit is ADET.';
  }

  return null;
}
