import { ProductPackageUnit } from './IProductPackageMetadata';

export interface IProductUploader {
  adminId: number;
  productName: string;
  subCategoryName: string;
  productPrice: number;
  productCount: number;
  productDiscount: number;
  productDescription: string;
  selectedImage: File | null;
  categoryValue: number;
  /**
   * Optional package size. Omitted - both fields absent - means "this product has
   * no package size", which is a legitimate state and not a missing value.
   *
   * Both fields or neither: the backend refuses a half-filled pair, because a
   * size without a measure is not a size and would leave the unit-price
   * calculation with no divisor.
   */
  packageAmount?: number | null;
  packageUnit?: ProductPackageUnit | string | null;
}
