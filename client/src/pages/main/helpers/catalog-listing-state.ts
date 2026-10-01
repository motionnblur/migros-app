import { ParamMap, Params } from '@angular/router';

import { ISubCategoryCount } from '../../../interfaces/ISubCategoryCount';
import {
  IProductSearchQuery,
  ProductSearchAvailability,
  ProductSearchSort,
} from '../../../interfaces/IProductSearchQuery';
import { parsePageParam } from './category-browse-state';

/**
 * The single bound between the URL and the catalogue search request.
 *
 * Both customer listings - the category page and the search results page - read
 * their state from these query parameters and send the same
 * `GET /user/supply/searchProducts` request built from it. That is deliberate: a
 * second copy of this translation is how two listings drift into answering
 * different questions for the same URL.
 *
 * Nothing here filters, sorts or counts a page. A listing that narrowed the rows
 * it happened to load would paginate before it filtered, so the pages would hold
 * products that do not match and the total would describe another set. Every
 * filter, every count and every ordering therefore stays on the server, and this
 * module only decides what to ask for and how to spell it in a shareable URL.
 */

/** The query parameter names the shared listings own. */
export const LISTING_QUERY_PARAM = {
  q: 'q',
  subcategory: 'subcategory',
  page: 'page',
  availability: 'availability',
  minPrice: 'minPrice',
  maxPrice: 'maxPrice',
  discounted: 'discounted',
  sort: 'sort',
} as const;

const LISTING_QUERY_PARAM_NAMES: readonly string[] = Object.values(LISTING_QUERY_PARAM);

/** The filter controls, in the order the toolbar renders them. */
export interface ListingFilterState {
  availability: ProductSearchAvailability;
  minPrice: string;
  maxPrice: string;
  discountedOnly: boolean;
  sort: ProductSearchSort;
}

export interface ListingSelectOption<T extends string> {
  value: T;
  label: string;
}

export const DEFAULT_AVAILABILITY: ProductSearchAvailability = 'ALL';
export const DEFAULT_SORT: ProductSearchSort = 'DEFAULT';

export const LISTING_AVAILABILITY_OPTIONS: readonly ListingSelectOption<ProductSearchAvailability>[] = [
  { value: 'ALL', label: 'Tümü' },
  { value: 'IN_STOCK', label: 'Stokta' },
  { value: 'OUT_OF_STOCK', label: 'Tükendi' },
];

export const LISTING_SORT_OPTIONS: readonly ListingSelectOption<ProductSearchSort>[] = [
  { value: 'DEFAULT', label: 'Varsayılan sıralama' },
  { value: 'PRICE_ASC', label: 'Fiyat: düşükten yükseğe' },
  { value: 'PRICE_DESC', label: 'Fiyat: yüksekten düşüğe' },
];

export function defaultListingFilters(): ListingFilterState {
  return {
    availability: DEFAULT_AVAILABILITY,
    minPrice: '',
    maxPrice: '',
    discountedOnly: false,
    sort: DEFAULT_SORT,
  };
}

export function availabilityLabel(value: ProductSearchAvailability): string {
  return (
    LISTING_AVAILABILITY_OPTIONS.find((option) => option.value === value)?.label ??
    value
  );
}

export function sortLabel(value: ProductSearchSort): string {
  return LISTING_SORT_OPTIONS.find((option) => option.value === value)?.label ?? value;
}

/**
 * Reads an availability out of a URL.
 *
 * Lenient on the way in and strict on the way out: a hand-edited or lowercase
 * `?availability=in_stock` becomes `IN_STOCK` rather than being forwarded and
 * answered with a 400, and anything unrecognized collapses to `ALL`, the
 * endpoint's own default. A listing that refused to render would be worse than
 * one that renders the unfiltered catalogue.
 */
export function normalizeListingAvailability(
  raw: string | null | undefined,
): ProductSearchAvailability {
  const value = (raw ?? '').trim().toUpperCase();
  return value === 'IN_STOCK' || value === 'OUT_OF_STOCK' ? value : DEFAULT_AVAILABILITY;
}

export function normalizeListingSort(raw: string | null | undefined): ProductSearchSort {
  const value = (raw ?? '').trim().toUpperCase();
  return value === 'PRICE_ASC' || value === 'PRICE_DESC' ? value : DEFAULT_SORT;
}

/**
 * Normalizes one price bound as typed.
 *
 * Accepts the Turkish decimal comma because that is what the field's locale
 * produces, and rejects anything the endpoint would refuse: a negative number,
 * more than two decimals, or free text. The empty string means "no bound", which
 * is what an untouched field is.
 */
export function normalizePriceText(raw: string | null | undefined): string {
  const trimmed = (raw ?? '').trim().replace(/\s/g, '').replace(',', '.');
  if (!trimmed) {
    return '';
  }

  if (!/^\d+(\.\d{1,2})?$/.test(trimmed)) {
    return '';
  }

  return trimmed;
}

export function parsePriceBound(raw: string | null | undefined): number | undefined {
  const normalized = normalizePriceText(raw);
  if (!normalized) {
    return undefined;
  }

  const parsed = Number(normalized);
  return Number.isFinite(parsed) ? parsed : undefined;
}

/**
 * The message shown when the typed bounds contradict each other.
 *
 * Returning `null` when they are consistent is what lets the caller refuse the
 * request instead of forwarding one the endpoint answers with a 400. The bounds
 * are not silently reordered: a customer who typed 50 into the minimum field and
 * 10 into the maximum field is told which of the two is impossible.
 */
export function priceRangeError(minPrice: string, maxPrice: string): string | null {
  const minimum = parsePriceBound(minPrice);
  const maximum = parsePriceBound(maxPrice);

  if (minimum === undefined || maximum === undefined) {
    return null;
  }

  return minimum > maximum
    ? 'En düşük fiyat, en yüksek fiyattan büyük olamaz.'
    : null;
}

/**
 * Normalizes a URL's price bounds into a request the endpoint will accept.
 *
 * A shared link cannot show an error before anything renders, so an inverted
 * pair is repaired by dropping the maximum: the page then shows the lower bound
 * it could honour rather than refusing to load.
 */
export function normalizePriceBounds(
  minPrice: string,
  maxPrice: string,
): { minPrice: string; maxPrice: string } {
  const minimum = normalizePriceText(minPrice);
  const maximum = normalizePriceText(maxPrice);
  const minimumValue = parsePriceBound(minimum);
  const maximumValue = parsePriceBound(maximum);

  if (minimumValue !== undefined && maximumValue !== undefined && minimumValue > maximumValue) {
    return { minPrice: minimum, maxPrice: '' };
  }

  return { minPrice: minimum, maxPrice: maximum };
}

export function listingFiltersFromParams(
  params: ParamMap | Params | null | undefined,
): ListingFilterState {
  const get = (key: string): string | null => {
    if (!params) {
      return null;
    }
    if (typeof (params as ParamMap).get === 'function') {
      return (params as ParamMap).get(key);
    }
    const value = (params as Params)[key];
    return value === undefined || value === null ? null : String(value);
  };

  const { minPrice, maxPrice } = normalizePriceBounds(
    get(LISTING_QUERY_PARAM.minPrice) ?? '',
    get(LISTING_QUERY_PARAM.maxPrice) ?? '',
  );

  return {
    availability: normalizeListingAvailability(get(LISTING_QUERY_PARAM.availability)),
    minPrice,
    maxPrice,
    discountedOnly: (get(LISTING_QUERY_PARAM.discounted) ?? '').trim() === 'true',
    sort: normalizeListingSort(get(LISTING_QUERY_PARAM.sort)),
  };
}

export function hasActiveListingFilters(filters: ListingFilterState | null | undefined): boolean {
  if (!filters) {
    return false;
  }

  return (
    filters.availability !== DEFAULT_AVAILABILITY ||
    normalizePriceText(filters.minPrice) !== '' ||
    normalizePriceText(filters.maxPrice) !== '' ||
    filters.discountedOnly === true ||
    filters.sort !== DEFAULT_SORT
  );
}

/** Builds the canonical query parameters for one listing state. */
export function buildListingQueryParams(input: {
  q?: string | null;
  subCategoryName?: string | null;
  page?: number | null;
  filters?: ListingFilterState | null;
}): Record<string, string> {
  const params: Record<string, string> = {};
  const term = (input.q ?? '').trim();
  const subCategoryName = (input.subCategoryName ?? '').trim();

  if (term) {
    params[LISTING_QUERY_PARAM.q] = term;
  }

  if (subCategoryName) {
    params[LISTING_QUERY_PARAM.subcategory] = subCategoryName;
  }

  const filters = input.filters ?? defaultListingFilters();
  const { minPrice, maxPrice } = normalizePriceBounds(filters.minPrice, filters.maxPrice);

  if (filters.availability !== DEFAULT_AVAILABILITY) {
    params[LISTING_QUERY_PARAM.availability] = filters.availability;
  }

  if (minPrice) {
    params[LISTING_QUERY_PARAM.minPrice] = minPrice;
  }

  if (maxPrice) {
    params[LISTING_QUERY_PARAM.maxPrice] = maxPrice;
  }

  if (filters.discountedOnly) {
    params[LISTING_QUERY_PARAM.discounted] = 'true';
  }

  if (filters.sort !== DEFAULT_SORT) {
    params[LISTING_QUERY_PARAM.sort] = filters.sort;
  }

  const page = input.page ?? 1;
  if (Number.isFinite(page) && Math.floor(page) > 1) {
    params[LISTING_QUERY_PARAM.page] = String(Math.floor(page));
  }

  return params;
}

/**
 * Copies only the listing's own parameters out of a route, so a link back to a
 * listing carries that listing's state and nothing else the URL happened to
 * hold.
 */
export function pickListingQueryParams(
  source: ParamMap | Params | null | undefined,
): Record<string, string> {
  const params: Record<string, string> = {};
  if (!source) {
    return params;
  }

  LISTING_QUERY_PARAM_NAMES.forEach((name) => {
    const raw =
      typeof (source as ParamMap).get === 'function'
        ? (source as ParamMap).get(name)
        : (source as Params)[name];
    const value = raw === null || raw === undefined ? '' : String(raw).trim();

    if (value) {
      params[name] = value;
    }
  });

  return params;
}

export function hasSameListingQueryParams(
  current: ParamMap | Params | null | undefined,
  desired: Record<string, string>,
): boolean {
  const picked = pickListingQueryParams(current);
  const currentKeys = Object.keys(picked);
  const desiredKeys = Object.keys(desired);

  if (currentKeys.length !== desiredKeys.length) {
    return false;
  }

  return desiredKeys.every((key) => picked[key] === desired[key]);
}

/**
 * Turns one listing state into the endpoint's request.
 *
 * Defaults are omitted rather than sent, because the endpoint's defaults are the
 * ones this listing means: `ALL` availability and `DEFAULT` sort. Sending them
 * would put the assumption in two places.
 *
 * The subcategory is dropped unless a category accompanies it, because the
 * endpoint answers 400 for a subcategory alone rather than quietly searching the
 * whole catalogue for that name.
 */
export function toProductSearchQuery(input: {
  q?: string | null;
  categoryId?: number | null;
  subCategoryName?: string | null;
  page?: number | null;
  size?: number;
  filters?: ListingFilterState | null;
}): IProductSearchQuery {
  const filters = input.filters ?? defaultListingFilters();
  const term = (input.q ?? '').trim();
  const { minPrice, maxPrice } = normalizePriceBounds(filters.minPrice, filters.maxPrice);
  const categoryId =
    input.categoryId !== null &&
    input.categoryId !== undefined &&
    Number.isFinite(input.categoryId) &&
    input.categoryId > 0
      ? Math.floor(input.categoryId)
      : undefined;
  const subCategoryName = (input.subCategoryName ?? '').trim();
  const requestedPage = input.page ?? 1;

  const query: IProductSearchQuery = {
    page: Math.max(0, Math.floor(requestedPage) - 1),
    size: input.size,
  };

  if (term) {
    query.q = term;
  }

  if (categoryId !== undefined) {
    query.categoryId = categoryId;

    if (subCategoryName) {
      query.subcategory = subCategoryName;
    }
  }

  if (filters.availability !== DEFAULT_AVAILABILITY) {
    query.availability = filters.availability;
  }

  const minimum = parsePriceBound(minPrice);
  if (minimum !== undefined) {
    query.minPrice = minimum;
  }

  const maximum = parsePriceBound(maxPrice);
  if (maximum !== undefined) {
    query.maxPrice = maximum;
  }

  if (filters.discountedOnly) {
    query.discountedOnly = true;
  }

  if (filters.sort !== DEFAULT_SORT) {
    query.sort = filters.sort;
  }

  return query;
}

/**
 * The stable key one listing request is identified by. Two navigations that
 * produce the same key are the same listing, so the host must not refetch.
 */
export function listingRequestSignature(input: Parameters<typeof toProductSearchQuery>[0]): string {
  return JSON.stringify(toProductSearchQuery(input));
}

/**
 * Keeps a requested subcategory name only when the server actually offered it.
 *
 * Used to repair a stale shared link. An empty bucket list is not treated as a
 * rejection: it means the category holds nothing for the active filters, not that
 * the name was wrong, and repairing it there would cost a pointless round trip.
 */
export function resolveListingSubCategoryName(
  subCategories: readonly ISubCategoryCount[] | null | undefined,
  requestedName: string | null | undefined,
): string {
  const requested = (requestedName ?? '').trim();
  if (!requested || !(subCategories ?? []).length) {
    return requested;
  }

  const match = (subCategories ?? []).find(
    (item) => item.subCategoryName === requested,
  );

  return match ? match.subCategoryName : '';
}

/** One rendered filter chip. `key` is what a remove action resets. */
export interface ListingFilterChip {
  key: 'availability' | 'price' | 'discounted' | 'sort';
  label: string;
}

const PRICE_FORMATTER = new Intl.NumberFormat('tr-TR', {
  minimumFractionDigits: 0,
  maximumFractionDigits: 2,
});

export function formatListingPrice(value: number): string {
  return PRICE_FORMATTER.format(value);
}

function formatPriceBound(raw: string): string {
  const parsed = parsePriceBound(raw);
  return parsed === undefined ? '' : `${formatListingPrice(parsed)} TL`;
}

/**
 * The applied filters, as removable chips.
 *
 * A chip is only rendered for a filter that actually narrows the result, and
 * each carries the key of the control it removes, so the listing can reset
 * exactly that one control instead of clearing everything.
 */
export function buildListingFilterChips(
  filters: ListingFilterState | null | undefined,
): ListingFilterChip[] {
  if (!filters) {
    return [];
  }

  const chips: ListingFilterChip[] = [];
  const { minPrice, maxPrice } = normalizePriceBounds(filters.minPrice, filters.maxPrice);
  const minimum = formatPriceBound(minPrice);
  const maximum = formatPriceBound(maxPrice);

  if (filters.availability !== DEFAULT_AVAILABILITY) {
    chips.push({
      key: 'availability',
      label: `Bulunabilirlik: ${availabilityLabel(filters.availability)}`,
    });
  }

  if (minimum && maximum) {
    chips.push({ key: 'price', label: `Fiyat: ${minimum} – ${maximum}` });
  } else if (minimum) {
    chips.push({ key: 'price', label: `Fiyat: ${minimum} ve üzeri` });
  } else if (maximum) {
    chips.push({ key: 'price', label: `Fiyat: ${maximum} ve altı` });
  }

  if (filters.discountedOnly) {
    chips.push({ key: 'discounted', label: 'Yalnızca indirimli ürünler' });
  }

  if (filters.sort !== DEFAULT_SORT) {
    chips.push({ key: 'sort', label: sortLabel(filters.sort) });
  }

  return chips;
}

export { parsePageParam };