export interface IProductUploader {
  adminId: number;
  productName: string;
  price: number;
  count: number;
  discount: number;
  description: string;
  selectedImage: File | null;
  categoryValue: number;
}
