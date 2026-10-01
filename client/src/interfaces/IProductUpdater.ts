import { ProductPackageUnit } from './IProductPackageMetadata';

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
  /**
   * Optional package size, on the same terms as `IProductUploader`: both fields
   * or neither, and `null` for both means the product keeps no package size.
   *
   * Clearing the fields is therefore how metadata is removed. There is no
   * separate "delete" call, because the two columns live on the product row and
   * this is already the write that owns the row - a second path for clearing
   * them would be a second way to skip the version check that every other field
   * on this row goes through.
   */
  packageAmount?: number | null;
  packageUnit?: ProductPackageUnit | string | null;
}
