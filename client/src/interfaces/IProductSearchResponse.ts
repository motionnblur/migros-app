import { IProductPreview } from './IProductPreview';
import { ISubCategoryCount } from './ISubCategoryCount';

/**
 * One page of catalogue search results, plus everything the listing needs to
 * render its filters and paginator without a second round trip.
 *
 * `totalItems` is the count of the fully filtered result before paging, so the
 * same number sizes the result counter and the page count. `subcategories` is
 * empty when no category was requested, because there is then nothing to switch
 * between.
 */
export interface IProductSearchResponse {
  items: IProductPreview[];
  totalItems: number;
  page: number;
  size: number;
  subcategories: ISubCategoryCount[];
}