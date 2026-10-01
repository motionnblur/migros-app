import {
  AfterViewInit,
  Component,
  ElementRef,
  EventEmitter,
  NgZone,
  OnDestroy,
  OnInit,
  Output,
  ViewChild,
} from '@angular/core';
import { loadStripe } from '@stripe/stripe-js';
import type { Stripe, StripeCardElement, StripeElements } from '@stripe/stripe-js';
import { Subscription } from 'rxjs';
import { data } from '../../../../memory/global-data';
import { RestService } from '../../../../services/rest/rest.service';
import { ICheckoutResponse } from '../../../../interfaces/ICheckoutResponse';
import { IPaymentStatus } from '../../../../interfaces/IPaymentStatus';
import {
  capturePreviousFocus,
  containTabKey,
  focusDialog,
  restoreFocus,
} from './dialog-a11y';
import { formatCurrencyLabel, formatMoney } from '../../helpers/money-format';

type CreatedStripeToken = NonNullable<
  Awaited<ReturnType<Stripe['createToken']>>['token']
>;

/**
 * The locale the Stripe card field is rendered in.
 *
 * <p>Stripe localizes its own placeholders and validation messages from this, and
 * `auto` resolves to whatever locale the browser reports - which for a Turkish
 * storefront is not a guarantee, and is certainly not the storefront's own
 * language. Pinning it means the card field says "Kart numarası" even on a device
 * set to something else.
 */
export const STRIPE_ELEMENT_LOCALE = 'tr';

/**
 * Every customer-visible string in the payment dialog, in one object.
 *
 * <p>Turkish is the storefront's language and the dialog used to be the one place
 * a customer was switched into English mid-checkout, including the states that
 * matter most - a charge that is still being verified, one that was refunded, and
 * one whose outcome is unknown. They are gathered here rather than inlined at
 * each throw site so that changing what the customer reads is a deliberate edit in
 * one place, and so a test can pin the wording without duplicating it.
 */
export const PAYMENT_COPY = {
  dialogTitle: 'Güvenli Ödeme',
  totalLabel: 'Toplam Tutar',
  preparing: 'Ödeme hazırlanıyor...',
  payNow: 'Ödemeyi Tamamla',
  processing: 'Ödeme işleniyor...',
  close: 'Ödeme penceresini kapat',
  cardFieldLabel: 'Kart bilgileri',
  /**
   * A statement about what this page does, and nothing more.
   *
   * <p>The dialog used to carry "By completing this transaction, you agree to our
   * Terms of Service and Privacy Policy" above two `href="#"` placeholders. There
   * is no terms or privacy document anywhere in the repository, so there was
   * nothing for either link to point at and no agreement to report the customer
   * had given. Rather than invent policy text, both links and the claim are gone;
   * what replaces them is verifiable from this component - the card is handed to
   * Stripe as a token and no card field is ever posted to the backend.
   */
  providerNote:
    'Kart bilgileriniz ödeme sağlayıcısına iletilir; kart numaranız bu sayfaya gönderilmez.',
  formInitFailed:
    'Ödeme formu yüklenemedi. Lütfen sayfayı yenileyip tekrar deneyin.',
  prepareFailed: 'Ödeme hazırlanamadı. Lütfen tekrar deneyin.',
  cardRejected:
    'Kart bilgileriniz kabul edilmedi. Lütfen kart bilgilerinizi kontrol edip tekrar deneyin.',
  tokenFailed: 'Kart bilgileriniz doğrulanamadı. Lütfen tekrar deneyin.',
  chargeFailed: 'Ödeme tamamlanamadı. Lütfen tekrar deneyin.',
  verifying: 'Ödemeniz doğrulanıyor. Lütfen bekleyin, tekrar göndermeyin.',
  verifyingOnClose:
    'Ödemeniz hâlâ doğrulanıyor. Lütfen bekleyin ve siparişlerinizi kontrol ettikten sonra tekrar deneyin.',
  stillProcessing:
    'Ödeme hâlâ işleniyor. Lütfen siparişlerinizi kontrol ettikten sonra tekrar deneyin.',
  refunded: 'Bu ödeme iade edildi.',
  unknownStatus:
    'Ödemenin durumu belirlenemedi. Lütfen siparişlerinizi kontrol edip tekrar deneyin.',
  verifyFailed:
    'Ödeme durumu doğrulanamadı. Lütfen siparişlerinizi kontrol ettikten sonra tekrar deneyin.',
  cancelUnverified:
    'Ödemenin iptali doğrulanamadı. Lütfen sipariş ve ödeme durumunuzu kontrol edip tekrar deneyin.',
  success: 'Ödemeniz başarıyla tamamlandı.',
} as const;

/**
 * Creates the Element group the card field is mounted into.
 *
 * <p>A named function rather than an inline `stripe.elements(...)` so the locale
 * is a decision a test can pin rather than an argument buried in an async
 * method the suite cannot run without the network.
 */
export function createCardElements(stripe: Stripe): StripeElements {
  return stripe.elements({ locale: STRIPE_ELEMENT_LOCALE });
}

@Component({
  selector: 'app-payment',
  templateUrl: './payment.component.html',
  styleUrls: ['./payment.component.css'],
})
export class PaymentComponent implements OnInit, AfterViewInit, OnDestroy {
  private static readonly MAX_STATUS_POLLS = 5;
  private static readonly STATUS_POLL_INTERVAL_MS = 2000;
  static readonly RECONCILIATION_PENDING_CODE =
    'PAYMENT_RECONCILIATION_PENDING';

  /** The dialog surface itself, the element that owns `role="dialog"`. */
  @ViewChild('paymentDialog') paymentDialogRef?: ElementRef<HTMLElement>;
  /** Where the Stripe card field is mounted, by reference rather than by id. */
  @ViewChild('cardMount') cardMountRef?: ElementRef<HTMLElement>;

  stripe: Stripe | null = null;
  elements: StripeElements | null = null;
  card: StripeCardElement | null = null;
  isProcessing: boolean = false;
  isPreparing: boolean = false;
  errorMessage: string = '';
  pendingMessage: string = '';
  /** What the card field itself objected to, while the customer is typing. */
  cardFieldError: string = '';
  checkout: ICheckoutResponse | null = null;
  displayTotal: string = '';
  displayCurrency: string = 'TRY';

  readonly dialogTitle = PAYMENT_COPY.dialogTitle;
  readonly closeLabel = PAYMENT_COPY.close;
  readonly totalLabel = PAYMENT_COPY.totalLabel;
  readonly preparing = PAYMENT_COPY.preparing;
  readonly cardFieldLabel = PAYMENT_COPY.cardFieldLabel;
  readonly providerNote = PAYMENT_COPY.providerNote;
  private readonly requests = new Subscription();
  private statusPollTimer: ReturnType<typeof setTimeout> | null = null;
  private destroyed = false;
  /** True once the card field has been placed in the DOM. */
  private cardMounted = false;
  /** Where focus came from, so closing can hand it back. */
  private readonly previouslyFocused = capturePreviousFocus();

  @Output() closePaymentComponentEvent = new EventEmitter<void>();
  @Output() paymentSuccess = new EventEmitter<void>();
  @Output() cartPrepared = new EventEmitter<void>();

  constructor(
    private restService: RestService,
    private zone: NgZone,
  ) {}

  ngOnInit() {
    void this.loadStripe();
    this.prepareCheckout();
  }

  ngAfterViewInit(): void {
    // Focus lands on the dialog so its name is announced before anything inside
    // it, and the Tab handlers are installed here because they need the element.
    focusDialog(this.paymentDialogRef?.nativeElement);
    document.addEventListener('keydown', this.keyHandler);
    // The Stripe script resolves after the view exists, but a cached script can
    // settle on a microtask that runs before the view is queried, so the mount is
    // retried here rather than assumed.
    this.mountCardField();
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    document.removeEventListener('keydown', this.keyHandler);
    if (this.statusPollTimer !== null) {
      clearTimeout(this.statusPollTimer);
      this.statusPollTimer = null;
    }
    this.requests.unsubscribe();
    this.card?.destroy();
    this.card = null;
    this.elements = null;
    this.stripe = null;
    // Last, so the customer is not left with focus on a node that is going away.
    restoreFocus(this.previouslyFocused);
  }

  /**
   * Escape closes the dialog and Tab stays inside it.
   *
   * <p>Escape is deliberately routed through `closePaymentComponent` rather than
   * straight to the close output: that method is the only thing that knows whether
   * a close may cancel, so a keyboard user must not be able to bypass it and
   * dismiss a checkout the way a backdrop click is not allowed to.
   *
   * <p>The cart's own handler ignores Escape while this dialog is open, so the two
   * never both react to one key press.
   */
  private readonly keyHandler = (event: KeyboardEvent) => {
    if (event.key === 'Escape') {
      this.closePaymentComponent();
      return;
    }
    if (event.key === 'Tab') {
      containTabKey(this.paymentDialogRef?.nativeElement, event);
    }
  };

  /** The total the server's snapshot resolved to, in the storefront's format. */
  get displayAmount(): string {
    return formatMoney(this.displayTotal, this.displayCurrency);
  }

  /** The currency name shown beside the amount, derived from the server's code. */
  get displayCurrencyLabel(): string {
    return formatCurrencyLabel(this.displayCurrency);
  }

  /**
   * What the submit button says.
   *
   * <p>One label for one state: a button that reads "Pay Now" while it is
   * disabled, or that changes its label before it actually refuses a press, tells
   * the customer two different things about the same click.
   */
  get submitLabel(): string {
    if (this.isPreparing) {
      return PAYMENT_COPY.preparing;
    }
    if (this.isProcessing) {
      return PAYMENT_COPY.processing;
    }
    return PAYMENT_COPY.payNow;
  }

  /**
   * Whether a charge for this checkout may already be in flight.
   *
   * <p>The local flag and the locally-moved checkout status are the same fact
   * asked twice, because either can be true on its own: the flag is set before
   * the provider is called, and the status is moved as soon as the request is
   * sent. Anything that must not create a replacement checkout asks this.
   */
  get isChargeInFlight(): boolean {
    return this.isProcessing || this.checkout?.status === 'PAYMENT_PROCESSING';
  }

  async loadStripe() {
    try {
      const stripe = await loadStripe(
        'pk_test_51R5GK1RpCkckemuqxqwmtU3jtnARLIiSxsxaeU8lg7wQrJJH8oUxH5ZdykHQCRvFNvSL4duOLcL6XQY5Cwkxjcvp00VDagc07P'
      );
      if (this.destroyed) {
        return;
      }
      if (!stripe) {
        this.errorMessage = PAYMENT_COPY.formInitFailed;
        return;
      }

      const elements = createCardElements(stripe);
      const card = elements.create('card');
      if (this.destroyed) {
        card.destroy();
        return;
      }
      this.stripe = stripe;
      this.elements = elements;
      this.card = card;
      this.mountCardField();
    } catch {
      if (!this.destroyed) {
        this.errorMessage = PAYMENT_COPY.formInitFailed;
      }
    }
  }

  /**
   * Mounts the card field and listens for what it rejects while the customer types.
   *
   * <p>Mounted on the element reference, not on `#card-element`: an id is a
   * document-wide claim, and a second dialog would silently mount over the first
   * one's field.
   *
   * <p>A mount that throws takes the card down and reports the form as
   * unavailable rather than leaving a field that can be filled in and never read -
   * which would look to the customer like a payment form that accepts anything.
   */
  private mountCardField(): void {
    // Only the Element group this component created has anything to mount. The
    // check also keeps the retry in `ngAfterViewInit` from reaching for a card
    // that was handed in from outside - which is how this component is exercised
    // without the network.
    const card = this.card;
    if (!this.elements || !card || this.cardMounted) {
      return;
    }
    const mountPoint = this.cardMountRef?.nativeElement;
    if (!mountPoint) {
      // The view is not queried yet; ngAfterViewInit calls back in.
      return;
    }
    try {
      card.mount(mountPoint);
    } catch {
      card.destroy();
      this.card = null;
      if (!this.destroyed) {
        this.errorMessage = PAYMENT_COPY.formInitFailed;
      }
      return;
    }
    this.cardMounted = true;
    // Localized with the same locale as the field itself, so a half-typed card
    // number is explained in the storefront's language instead of being announced
    // as a failed payment.
    //
    // Re-entered explicitly because Stripe delivers this from its own listener
    // inside its iframe, and whether that lands in Angular's zone is the script's
    // business rather than ours. A message that never reaches change detection
    // would sit invisible until the next unrelated click.
    card.on('change', (event) =>
      this.zone.run(() => {
        this.cardFieldError = event.error?.message ?? '';
      }),
    );
  }

  // The server computes the immutable checkout snapshot. The client only
  // displays the returned total and never supplies prices, totals or currency.
  prepareCheckout() {
    if (this.isPreparing || this.isProcessing) {
      return;
    }
    // Never start a replacement checkout while the previous one may have money
    // in flight. The server also rejects this with a conflict, but the client
    // must not even attempt it.
    if (this.checkout?.status === 'PAYMENT_PROCESSING') {
      return;
    }
    this.isPreparing = true;
    this.requests.add(this.restService.prepareCheckout().subscribe({
      next: (checkout: ICheckoutResponse) => {
        this.checkout = checkout;
        this.displayTotal = Number(checkout.totalAmount).toFixed(2);
        this.displayCurrency = (checkout.currency || 'try').toUpperCase();
        this.isPreparing = false;
        // The reserved snapshot has been removed from the live cart server-side.
        this.cartPrepared.emit();
      },
      error: () => {
        this.isPreparing = false;
        this.errorMessage = PAYMENT_COPY.prepareFailed;
      },
    }));
  }

  async handlePayment() {
    // UI double-submit protection. The server-side payment-attempt lease and
    // idempotency key remain the authoritative control.
    if (this.isProcessing || !this.checkout) {
      return;
    }

    this.errorMessage = '';
    this.pendingMessage = '';
    this.cardFieldError = '';
    if (!this.stripe || !this.card) {
      this.errorMessage = PAYMENT_COPY.formInitFailed;
      return;
    }
    this.isProcessing = true;

    try {
      const { token, error } = await this.stripe.createToken(this.card);
      if (this.destroyed) {
        return;
      }

      if (error || !token) {
        this.errorMessage = this.describeCardFailure(error?.message);
        this.isProcessing = false;
        return;
      }

      this.processPayment(token);
    } catch {
      if (!this.destroyed) {
        this.errorMessage = PAYMENT_COPY.tokenFailed;
        this.isProcessing = false;
      }
    }
  }

  /**
   * Explains a card the provider would not tokenize.
   *
   * <p>The provider's own sentence is kept rather than replaced, because it names
   * the reason - an expired card, a wrong digit - and paraphrasing it would only
   * lose that. It is led by our own Turkish explanation so the customer is not
   * left reading an English sentence in the middle of a Turkish checkout, and it
   * says the card was not accepted rather than anything that would read as a
   * successful outcome: a rejected token means no charge was attempted at all.
   */
  private describeCardFailure(providerMessage?: string): string {
    const detail = (providerMessage ?? '').trim();
    return detail.length > 0
      ? `${PAYMENT_COPY.cardRejected} (${detail})`
      : PAYMENT_COPY.cardRejected;
  }

  processPayment(token: CreatedStripeToken) {
    const checkoutId = this.checkout!.checkoutId;
    // Mark the local snapshot as processing so a modal close can never treat
    // it as a plain prepared checkout. The server moves it to
    // PAYMENT_PROCESSING during the claim before the provider call.
    if (this.checkout) {
      this.checkout.status = 'PAYMENT_PROCESSING';
    }

    this.requests.add(this.restService.chargeCheckout(checkoutId, token.id).subscribe({
      next: (response) => {
        if (response.success && !response.pending) {
          this.completePayment();
        } else if (response.pending) {
          // The charge may already have succeeded; never start a new checkout.
          this.pollPaymentStatus(checkoutId, PaymentComponent.MAX_STATUS_POLLS);
        } else {
          this.isProcessing = false;
          this.errorMessage = PAYMENT_COPY.chargeFailed;
        }
      },
      // A network timeout is an unknown result: recover the existing checkout
      // instead of starting a new one.
      error: () =>
        this.pollPaymentStatus(checkoutId, PaymentComponent.MAX_STATUS_POLLS),
    }));
  }

  /**
   * Polls the authenticated status endpoint for the SAME checkout. The server
   * retries order finalization on status access and returns the durable attempt
   * state, so an ambiguous network outcome can never cause a second charge.
   */
  pollPaymentStatus(checkoutId: string, remainingPolls: number) {
    this.requests.add(this.restService.getPaymentStatus(checkoutId).subscribe({
      next: (status) => this.applyPaymentStatus(status, checkoutId, remainingPolls),
      error: () => {
        this.isProcessing = false;
        this.errorMessage = PAYMENT_COPY.verifyFailed;
      },
    }));
  }

  private applyPaymentStatus(
    status: IPaymentStatus,
    checkoutId: string,
    remainingPolls: number,
  ) {
    if (status.finalized) {
      this.completePayment();
      return;
    }

    if (status.pending && remainingPolls > 0) {
      this.pendingMessage = PAYMENT_COPY.verifying;
      this.statusPollTimer = setTimeout(
        () => {
          this.statusPollTimer = null;
          if (!this.destroyed) {
            this.pollPaymentStatus(checkoutId, remainingPolls - 1);
          }
        },
        PaymentComponent.STATUS_POLL_INTERVAL_MS,
      );
      return;
    }

    this.isProcessing = false;
    this.pendingMessage = '';

    if (status.pending) {
      this.errorMessage = PAYMENT_COPY.stillProcessing;
    } else if (status.refunded) {
      this.errorMessage = PAYMENT_COPY.refunded;
    } else if (status.state === 'FAILED_FINAL') {
      this.errorMessage = PAYMENT_COPY.chargeFailed;
    } else {
      this.errorMessage = PAYMENT_COPY.unknownStatus;
    }
  }

  private completePayment() {
    this.isProcessing = false;
    this.pendingMessage = '';
    data.totalCartPrice = 0;
    this.paymentSuccess.emit();
    this.closePaymentComponentEvent.emit();
    // Reached only once durable status says the order was finalized, so this is
    // the one place in the component that may claim the money was taken - and it
    // still says so in the storefront's language.
    alert(PAYMENT_COPY.success);
  }

  public closePaymentComponent() {
    // A processing checkout may have a charge in flight: never cancel it.
    // Keep polling the same checkout or tell the user verification is pending.
    // Only an untouched PREPARED checkout may be cancelled to return stock.
    if (this.isChargeInFlight) {
      this.pendingMessage = PAYMENT_COPY.verifyingOnClose;
      return;
    }
    const checkoutId = this.checkout?.checkoutId;
    if (checkoutId && this.checkout?.status === 'PREPARED') {
      this.requests.add(this.restService.cancelCheckout(checkoutId).subscribe({
        next: () => {
          this.checkout = null;
          this.closePaymentComponentEvent.emit();
        },
        error: (error) => this.handleCancelConflict(checkoutId, error),
      }));
      return;
    }
    this.closePaymentComponentEvent.emit();
  }

  isReconciliationPendingConflict(error: unknown): boolean {
    if (error === null || typeof error !== 'object') {
      return false;
    }

    const errorResponse = error as { status?: unknown; error?: unknown };
    const body = errorResponse.error;
    return (
      errorResponse.status === 409 &&
      body !== null &&
      typeof body === 'object' &&
      'code' in body &&
      body.code === PaymentComponent.RECONCILIATION_PENDING_CODE &&
      'pending' in body &&
      body.pending === true
    );
  }

  private handleCancelConflict(checkoutId: string, error: unknown): void {
    if (this.isReconciliationPendingConflict(error)) {
      // Reconciliation is pending: keep the SAME checkout and recover through
      // the existing status endpoint. Never prepare a replacement checkout.
      if (this.checkout) {
        this.checkout.status = 'PAYMENT_PROCESSING';
      }
      this.pendingMessage = PAYMENT_COPY.stillProcessing;
      this.pollPaymentStatus(checkoutId, PaymentComponent.MAX_STATUS_POLLS);
      return;
    }
    // Fail closed on the UI as well: the reservation stays server-side
    // for status recovery even if the modal closes. Never surface provider
    // internals; advise checking order/payment status.
    this.errorMessage = PAYMENT_COPY.cancelUnverified;
    this.checkout = null;
    this.closePaymentComponentEvent.emit();
  }
}