/**
 * One Turkish rendering of every number the storefront shows a customer.
 *
 * <p>The product card printed `30.00 TL`, the product detail printed `30.00 TL`
 * and the cart printed `30,00 TL` - for the same server-sent amount. Two of those
 * used `Number.prototype.toFixed`, which is locale-free and therefore always
 * renders the en-US separators, and the third used an `Intl` formatter. A customer
 * comparing a card with the cart it was added to was being shown two renderings
 * of one price, and neither was in the storefront's own notation.
 *
 * <p>So this module is the single owner, and it is a formatter and nothing more:
 * the amount that arrives is the amount that goes out, only written differently.
 * No rounding decision, no scaling, no currency of its own. The unit price is
 * divided by the backend and the payable total is the server's checkout snapshot;
 * both are already at the money scale and are reproduced verbatim here.
 *
 * <p>It also owns the package *amount* - `1,5 L` beside `24,99 TL/kg` rather than
 * `1.5 L` beside it. A quantity is not money, so it is formatted at its own
 * precision, but the decimal separator a customer reads on a card has to be the
 * same one they read on the price next to it.
 *
 * <p>It lives under `helpers/` because it is used by the catalogue, the cart and
 * the checkout alike. Under `payment/` it was reachable only by the two checkout
 * surfaces, which is exactly the boundary that let the catalogue keep its own
 * copy.
 */

/**
 * The storefront's notation.
 *
 * <p>Pinned rather than inherited: `toFixed` and Angular's `number` pipe both
 * follow whatever the browser happens to report, so a Turkish storefront rendered
 * `30,00` on a Turkish device and `30.00` everywhere else. Naming the locale is
 * what makes the rendering the same on every device, and it is exported so a test
 * can assert the storefront has exactly one.
 */
export const STOREFRONT_LOCALE = 'tr-TR';

/** The only currency the backend currently settles in (`PAYMENT_CURRENCY`). */
const FALLBACK_CURRENCY = 'TRY';

/**
 * Money, always at two decimals.
 *
 * <p>Two decimals always, because a charge is made in minor units and a total
 * shown as `21` reads as `21,00` to nobody - and because every price the backend
 * produces is `NUMERIC(19,2)`, so a second decimal is implied precision the
 * number does not carry.
 */
const amountFormatter = new Intl.NumberFormat(STOREFRONT_LOCALE, {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/**
 * A quantity, at up to three decimals and no more.
 *
 * <p>Three is the scale a package amount can carry (a dose in millilitres, a spice
 * in grams) and the minimum is left at zero so trailing zeros are still trimmed:
 * `1.500` renders as `1,5`, while `0.125` keeps all three.
 */
const quantityFormatter = new Intl.NumberFormat(STOREFRONT_LOCALE, {
  maximumFractionDigits: 3,
});

/**
 * How a currency code is written for a customer.
 *
 * <p>Only `TRY` has a local name worth showing; anything else the API might
 * return is passed through in its own code form rather than guessed at, because a
 * mislabelled currency is worse than an unfamiliar one.
 */
const CURRENCY_LABELS: Readonly<Record<string, string>> = { TRY: 'TL' };

/** Formats a money amount in Turkish, with two decimals. */
export function formatAmount(value: number | string | null | undefined): string {
  const amount = typeof value === 'string' ? Number(value) : value;
  if (amount === null || amount === undefined || !Number.isFinite(amount)) {
    // An unparseable amount is a display bug, not a value to invent: show the
    // neutral form and let the surrounding status line explain the real state.
    return amountFormatter.format(0);
  }
  return amountFormatter.format(amount);
}

/**
 * Formats a package quantity in Turkish, at up to three decimals.
 *
 * <p>Not a money formatter: a package amount is a measure, and rounding `0.125 KG`
 * to `0.13 KG` would misreport what the customer is buying.
 */
export function formatQuantity(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return quantityFormatter.format(0);
  }
  return quantityFormatter.format(value);
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
