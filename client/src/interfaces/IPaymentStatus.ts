export interface IPaymentStatus {
  checkoutId: string;
  attemptId?: string | null;
  checkoutStatus: string;
  state?: string | null;
  chargeId?: string | null;
  totalAmount?: number | null;
  amountMinor?: number | null;
  currency?: string | null;
  orderGroupId?: number | null;
  finalized: boolean;
  pending: boolean;
  refunded: boolean;
}
