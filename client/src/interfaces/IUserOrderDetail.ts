export interface IUserOrderDetail {
  orderId: number;
  productId: number;
  productName: string;
  count: number;
  price: number;
  totalPrice: number;
  status: string;
  productImageUrl?: string;
}
