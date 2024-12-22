export interface IProductUploader {
  productName: string;
  price: number;
  count: number;
  discount: number;
  description: string;
  selectedImage: File | null;
}
