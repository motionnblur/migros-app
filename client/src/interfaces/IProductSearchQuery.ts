/**
 * The catalogue search request, mirroring `GET /user/supply/searchProducts`.
 *
 * Every field is optional because the endpoint's defaults are meaningful:
 * `availability` defaults to `ALL` (sold-out products included on purpose) and
 * `sort` defaults to `DEFAULT` (in stock first, then product id ascending), so
 * sending nothing is the same request as sending those two explicitly. Absent
 * values are omitted from the query string rather than serialized as `null`.
 *
 * `page` is the zero-based index the endpoint expects, which is one less than
 * the page the UI shows; the conversion belongs to the caller, not to here.
 *
 * `subcategory` is only meaningful together with `categoryId` - the endpoint
 * answers 400 for a subcategory on its own, so the two must always be sent as a
 * pair.
 */
export interface IProductSearchQuery {
  q?: string;
  categoryId?: number;
  subcategory?: string;
  availability?: ProductSearchAvailability;
  minPrice?: number;
  maxPrice?: number;
  discountedOnly?: boolean;
  sort?: ProductSearchSort;
  page?: number;
  size?: number;
}

/** Case sensitive: the endpoint rejects any other casing with a 400. */
export type ProductSearchAvailability = 'ALL' | 'IN_STOCK' | 'OUT_OF_STOCK';

/** Case sensitive: the endpoint rejects any other casing with a 400. */
export type ProductSearchSort = 'DEFAULT' | 'PRICE_ASC' | 'PRICE_DESC';