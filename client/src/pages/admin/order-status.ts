export type OrderStatusVariant =
  | 'success'
  | 'warning'
  | 'danger'
  | 'info'
  | 'neutral';

/**
 * Maps the order status strings produced by the backend to a consistent badge
 * variant used across the admin panel.
 */
export function orderStatusVariant(status: string): OrderStatusVariant {
  const value = (status ?? '').toLowerCase();

  if (
    value.includes('delivered') ||
    value.includes('teslim') ||
    value.includes('tamam')
  ) {
    return 'success';
  }

  if (
    value.includes('shipped') ||
    value.includes('kargo') ||
    value.includes('out for delivery') ||
    value.includes('yolda') ||
    value.includes('dağıtım') ||
    value.includes('dagitim')
  ) {
    return 'info';
  }

  if (
    value.includes('ordered') ||
    value.includes('alındı') ||
    value.includes('alindi') ||
    value.includes('bekliyor')
  ) {
    return 'warning';
  }

  if (value.includes('cancel') || value.includes('iptal')) {
    return 'danger';
  }

  return 'neutral';
}
