/**
 * The shared browsing arithmetic that is not about the query string: the page size
 * both listings use, and the page-number rules that go with it.
 *
 * The URL translation itself lives in `catalog-listing-state`, which owns every
 * filter parameter as well. Keeping the two halves apart is deliberate - the
 * clamping rules are pure numbers and are the same wherever they are called from,
 * while the parameter names belong to the one bound that talks to the catalogue
 * search endpoint.
 */

export const PRODUCT_PAGE_SIZE = 10;

export const PAGE_QUERY_PARAM = 'page';

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