export function productCartErrorMessage(
  error: unknown,
  fallback = 'Ürün sepete eklenemedi.',
): string {
  if (typeof error !== 'object' || error === null || !('error' in error)) {
    return fallback;
  }

  const body = error.error;
  const message = typeof body === 'string' ? body.trim() : '';
  return message || fallback;
}
