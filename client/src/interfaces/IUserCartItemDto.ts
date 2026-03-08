export interface IUserCartItemDto {
  productId: number;
  productName: string;
  productPrice: number;
  productCount: number;
  availableStock: number;
  productImageUrl?: string;
  deleteState?: boolean;
}
