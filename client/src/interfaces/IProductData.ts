export interface IProductData {
  productName?: string;
  subCategoryName?: string;
  productPrice?: number;
  productCount?: number;
  productDiscount?: number;
  productDescription?: string;
  productCategoryId?: number;
  /**
   * The product's edit version as observed by this read.
   *
   * Send it back as `IProductUpdater.expectedVersion`. A product update that
   * omits it is rejected, and one that sends a stale value is rejected with
   * HTTP 409, because the absolute stock count in an edit form goes stale as
   * soon as a checkout reserves units or an order is restocked.
   */
  productVersion?: number;
}
