/**
 * The three keyboard jobs a hand-rolled dialog has to do itself.
 *
 * <p>The storefront's modals are not Angular Material dialogs - they are a
 * `position: fixed` backdrop plus a card, rendered onto the named `modal`
 * outlet - so nothing gives them a name, moves focus, or keeps Tab inside them
 * for free. Declaring `role="dialog"` and `aria-modal="true"` without doing the
 * other two is worse than declaring neither: a screen reader is told the rest of
 * the page is gone while the keyboard is still wandering through it. These three
 * functions are the whole of that, and they are shared by the cart and the
 * payment dialog that opens on top of it so the two cannot disagree.
 *
 * <p>It lives under `payment/` for the same reason the payment component does:
 * the payment dialog is nested inside the cart, so the cart is the one that
 * imports it, and the smaller module should not have to depend on the larger.
 */

/**
 * Everything that can hold focus, minus what is explicitly removed from the tab
 * order. The container itself is not listed - it is focusable through
 * `tabindex="-1"`, which is how a dialog gets focus before its first control, not
 * a stop in the Tab sequence.
 */
const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

/**
 * The element that had focus when a dialog opened, so it can be given it back.
 *
 * <p>Captured before the dialog moves focus anywhere, and only when it is
 * something focus can usefully return to: `body` is what a dialog opened by a
 * keyboard shortcut or a deep link inherits, and focusing it again would move
 * the caret nowhere at all.
 */
export function capturePreviousFocus(): HTMLElement | null {
  const active = document.activeElement;
  if (
    active === null ||
    active === document.body ||
    active === document.documentElement
  ) {
    return null;
  }
  return active instanceof HTMLElement ? active : null;
}

/**
 * Moves focus onto the dialog itself rather than onto its first control.
 *
 * <p>That is the announced unit: focusing the container makes the dialog's
 * accessible name the first thing read out, and leaves the customer free to Tab
 * onwards rather than having a "Close" button silently swallowed as their first
 * keystroke.
 */
export function focusDialog(container: HTMLElement | null | undefined): void {
  container?.focus();
}

/**
 * Gives focus back to wherever the dialog was opened from.
 *
 * <p>Skipped when that element is gone. The dialog can be torn down by a route
 * that also replaced what was behind it - closing the cart from the browser Back
 * button is the ordinary case - and focusing a detached node is a silent no-op
 * that would look like the feature working.
 */
export function restoreFocus(previous: HTMLElement | null | undefined): void {
  if (!previous || !previous.isConnected) {
    return;
  }
  previous.focus();
}

/**
 * Keeps Tab inside the dialog.
 *
 * <p>`aria-modal="true"` promises that the page behind the dialog is out of
 * reach, so a Tab that walks out of it would contradict the promise: the customer
 * would be editing the storefront with a modal still up. Wraps at both ends and
 * steps in from the container itself, which is where focus starts.
 *
 * <p>Returns whether it handled the key, so a caller can tell a wrap from an
 * ordinary Tab that should be left alone.
 */
export function containTabKey(
  container: HTMLElement | null | undefined,
  event: KeyboardEvent,
): boolean {
  if (event.key !== 'Tab' || !container) {
    return false;
  }

  const focusable = Array.from(
    container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR),
  );

  if (focusable.length === 0) {
    event.preventDefault();
    container.focus();
    return true;
  }

  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  const active = document.activeElement as HTMLElement | null;
  const isOutside = active === null || !container.contains(active);

  if (event.shiftKey && (active === first || active === container || isOutside)) {
    event.preventDefault();
    last.focus();
    return true;
  }

  if (!event.shiftKey && (active === last || active === container || isOutside)) {
    event.preventDefault();
    first.focus();
    return true;
  }

  return false;
}