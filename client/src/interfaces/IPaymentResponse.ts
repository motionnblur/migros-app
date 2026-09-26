export interface IPaymentResponse {
  success: boolean;
  pending: boolean;
  checkoutId: string;
  status: string;
  chargeId?: string | null;
  totalAmount?: number | null;
  amountMinor?: number | null;
  currency?: string | null;
  error?: string | null;
}
