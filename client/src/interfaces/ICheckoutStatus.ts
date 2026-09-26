export interface ICheckoutStatus {
  checkoutId: string;
  status: string;
  totalAmount: number;
  amountMinor: number;
  currency: string;
  createdAt?: string;
  expiresAt?: string;
  orderGroupId?: number | null;
  chargeId?: string | null;
}
