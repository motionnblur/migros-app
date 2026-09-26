export interface ICheckoutResponse {
  checkoutId: string;
  status: string;
  totalAmount: number;
  amountMinor: number;
  currency: string;
  createdAt?: string;
  expiresAt?: string;
}
