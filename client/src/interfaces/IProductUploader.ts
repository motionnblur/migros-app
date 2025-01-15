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
}
