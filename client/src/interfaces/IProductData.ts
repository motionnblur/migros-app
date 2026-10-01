import { ProductUnitPriceBasis } from './IProductPackageMetadata';

export interface IProductData {
  productName?: string;
  subCategoryName?: string;
  productPrice?: number;
  productCount?: number;
  productDiscount?: number;
  productDescription?: string;
  productCategoryId?: number;
  /**
   * The price actually payable for this product: already discounted and rounded
   * to the money scale by the server.
   *
   * Render this rather than recomputing one from `productPrice` and
   * `productDiscount`. A browser recomputation of the discount does not agree with
   * the backend's rounding sequence at the boundaries - a stored 10.10 with a 5
   * percent discount is 9.60 here and 9.59 by the arithmetic in the browser - so
   * recomputing it is how the detail page ends up quoting a cent less than the
   * card, the cart line and the charge.
   *
   * `productPrice` and `productDiscount` remain the stored columns and are still
   * what the struck-through original and the percentage are shown from.
   */
  effectivePrice?: number | null;
  /**
   * The product's edit version as observed by this read.
   *
   * Send it back as `IProductUpdater.expectedVersion`. A product update that
   * omits it is rejected, and one that sends a stale value is rejected with
   * HTTP 409, because the absolute stock count in an edit form goes stale as
   * soon as a checkout reserves units or an order is restocked.
   */
  productVersion?: number;
  /**
   * The stored package size, or `null`/`undefined` when the product has none.
   *
   * The admin editor reads this to prefill its optional fields, so it carries
   * what is stored rather than a display formatting of it. The same two fields
   * also reach the customer detail page, which is why they are read-only there:
   * only an edit may change them, and it must go through the version-checked
   * update like every other field.
   */
  packageAmount?: number | null;
  packageUnit?: string | null;
  /** The server-computed price per basis unit, or `null` when there is no size. */
  unitPrice?: number | null;
  unitPriceBasis?: ProductUnitPriceBasis | string | null;
}
