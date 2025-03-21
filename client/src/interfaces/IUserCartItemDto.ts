export interface IUserCartItemDto {
  productId: number;
  productName: string;
  productPrice: number;
  productCount: number;
  productImageUrl?: string;
  deleteState?: boolean;
}
