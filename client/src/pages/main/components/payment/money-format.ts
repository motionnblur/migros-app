/**
 * One presentation of a money amount for the whole checkout flow.
 *
 * <p>The cart footer and the payment dialog show the same two facts - what the
 * cart adds up to, and what the server's checkout snapshot says is payable - and
 * a customer who sees two different renderings of them while walking from one to
 * the other has been given a reason to doubt both. They also disagreed on the
 * currency: the cart printed `TL` while the dialog printed the raw `TRY` code
 * that arrived from the API. So the amount is formatted in Turkish in both
 * places, and the currency keeps coming from the server - it is a display label
 * derived from the code the API returned, never a currency the client chooses.
 *
 * <p>It lives under `payment/` because the checkout snapshot is the authoritative
 * amount; the cart reuses it rather than keeping a second copy that could drift.
 */

/** The only currency the backend currently settles in (`PAYMENT_CURRENCY`). */
const FALLBACK_CURRENCY = 'TRY';

/**
 * Amounts are rendered in Turkish: a thousands separator of `.` and a decimal
 * separator of `,`.
 *
 * <p>Two decimals always, because a charge is made in minor units and a total
 * shown as `21` reads as `21,00` to nobody.
 */
const amountFormatter = new Intl.NumberFormat('tr-TR', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/**
 * How a currency code is written for a customer.
 *
 * <p>Only `TRY` has a local name worth showing; anything else the API might
 * return is passed through in its own code form rather than guessed at, because a
 * mislabelled currency is worse than an unfamiliar one.
 */
const CURRENCY_LABELS: Readonly<Record<string, string>> = { TRY: 'TL' };

/** Formats an amount in Turkish, with two decimals. */
export function formatAmount(value: number | string | null | undefined): string {
  const amount = typeof value === 'string' ? Number(value) : value;
  if (amount === null || amount === undefined || !Number.isFinite(amount)) {
    // An unparseable amount is a display bug, not a value to invent: show the
    // neutral form and let the surrounding status line explain the real state.
    return amountFormatter.format(0);
  }
  return amountFormatter.format(amount);
}

/** The customer-facing name of a currency code the API returned. */
export function formatCurrencyLabel(
  currency: string | null | undefined,
): string {
  const code = (currency ?? '').trim().toUpperCase();
  if (code.length === 0) {
    return CURRENCY_LABELS[FALLBACK_CURRENCY];
  }
  return CURRENCY_LABELS[code] ?? code;
}

/** An amount and its currency, as one string: `1.234,56 TL`. */
export function formatMoney(
  value: number | string | null | undefined,
  currency: string | null | undefined,
): string {
  return `${formatAmount(value)} ${formatCurrencyLabel(currency)}`;
}