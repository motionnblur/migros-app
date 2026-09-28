import { Params } from '@angular/router';

import { ISubCategory } from '../../../interfaces/ISubCategory';

export const PRODUCT_PAGE_SIZE = 10;

export const SUBCATEGORY_QUERY_PARAM = 'subcategory';
export const PAGE_QUERY_PARAM = 'page';

export type BrowseQueryParams = Record<string, string>;

/**
 * Parses the `page` query parameter. Anything that is not a positive whole
 * number (`0`, `-3`, `1.5`, `abc`, `1e2`, empty) is treated as absent so the
 * caller falls back to the first page instead of requesting a broken page.
 */
export function parsePageParam(raw: string | null | undefined): number | null {
  if (raw === null || raw === undefined) {
    return null;
  }

  const trimmed = String(raw).trim();
  if (!/^\d+$/.test(trimmed)) {
    return null;
  }

  const parsed = Number(trimmed);
  if (!Number.isSafeInteger(parsed) || parsed < 1) {
    return null;
  }

  return parsed;
}

/**
 * Returns the requested subcategory name only when the loaded subcategory list
 * actually contains it. Unknown names collapse to the default "all products"
 * selection so a hand-edited or stale link still renders a valid listing.
 */
export function resolveSubCategoryName(
  subCategories: readonly ISubCategory[] | null | undefined,
  requestedName: string | null | undefined,
): string {
  const requested = (requestedName ?? '').trim();
  if (!requested) {
    return '';
  }

  const match = (subCategories ?? []).find(
    (item) => item.subCategoryName === requested,
  );

  return match ? match.subCategoryName : '';
}

export function countForSelection(
  subCategories: readonly ISubCategory[] | null | undefined,
  categoryProductCount: number,
  selectedSubCategoryName: string,
): number {
  const selected = (selectedSubCategoryName ?? '').trim();
  if (!selected) {
    return Math.max(0, categoryProductCount ?? 0);
  }

  const match = (subCategories ?? []).find(
    (item) => item.subCategoryName === selected,
  );

  return match ? Math.max(0, match.productCount ?? 0) : 0;
}

export function pageCountForProductCount(
  productCount: number,
  pageSize: number = PRODUCT_PAGE_SIZE,
): number {
  const size = Number.isFinite(pageSize) && pageSize > 0 ? Math.floor(pageSize) : PRODUCT_PAGE_SIZE;
  const count = Number.isFinite(productCount) ? productCount : 0;

  if (count <= 0) {
    return 1;
  }

  return Math.max(1, Math.ceil(count / size));
}

export function clampPageToRange(page: number | null | undefined, pageCount: number): number {
  const upperBound = Math.max(1, Math.floor(pageCount) || 1);
  const requested = page ?? 1;

  if (!Number.isFinite(requested)) {
    return 1;
  }

  return Math.min(Math.max(1, Math.floor(requested)), upperBound);
}

/**
 * Builds the canonical query parameters for a browsing state. The default
 * selection and the first page are omitted so the shared URL stays clean.
 */
export function buildBrowseQueryParams(
  subCategoryName: string | null | undefined,
  page: number | null | undefined,
): BrowseQueryParams {
  const params: BrowseQueryParams = {};
  const name = (subCategoryName ?? '').trim();

  if (name) {
    params[SUBCATEGORY_QUERY_PARAM] = name;
  }

  const requestedPage = page ?? 1;
  if (Number.isFinite(requestedPage) && Math.floor(requestedPage) > 1) {
    params[PAGE_QUERY_PARAM] = String(Math.floor(requestedPage));
  }

  return params;
}

export function hasSameBrowseQueryParams(
  current: Params | null | undefined,
  desired: BrowseQueryParams,
): boolean {
  const currentKeys = Object.keys(current ?? {}).filter(
    (key) => (current as Record<string, unknown>)[key] !== null &&
      (current as Record<string, unknown>)[key] !== undefined,
  );
  const desiredKeys = Object.keys(desired);

  if (currentKeys.length !== desiredKeys.length) {
    return false;
  }

  return desiredKeys.every(
    (key) => String((current as Record<string, unknown>)[key]) === desired[key],
  );
}
