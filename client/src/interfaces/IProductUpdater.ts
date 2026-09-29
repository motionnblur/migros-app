export interface IProductUpdater {
  adminId: number;
  productId: number;
  productName: string;
  subCategoryName: string;
  productPrice: number;
  productCount: number;
  productDiscount: number;
  productDescription: string;
  selectedImage: File | null | undefined;
  categoryValue: number;
  /**
   * The product version this edit was started from, captured when the form was
   * loaded from `IProductData.productVersion`.
   *
   * Required, and never guessed. The backend compares it against the row under
   * a write lock and answers 409 `PRODUCT_EDIT_CONFLICT` when they differ, which
   * is what stops an edit form that has been open since before a checkout from
   * writing its stale `productCount` over stock that is already reserved. On a
   * conflict the draft must be kept and the product reloaded deliberately: the
   * value must not be refreshed and resubmitted automatically.
   */
  expectedVersion: number;
}
