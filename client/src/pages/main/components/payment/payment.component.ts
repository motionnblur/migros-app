import { Component, EventEmitter, OnInit, Output } from '@angular/core';
import { loadStripe } from '@stripe/stripe-js';
import { data } from '../../../../memory/global-data';
import { RestService } from '../../../../services/rest/rest.service';
import { ICheckoutResponse } from '../../../../interfaces/ICheckoutResponse';
import { IPaymentStatus } from '../../../../interfaces/IPaymentStatus';

@Component({
  selector: 'app-payment',
  templateUrl: './payment.component.html',
  styleUrls: ['./payment.component.css'],
})
export class PaymentComponent implements OnInit {
  private static readonly MAX_STATUS_POLLS = 5;
  private static readonly STATUS_POLL_INTERVAL_MS = 2000;

  stripe: any;
  elements: any;
  card: any;
  isProcessing: boolean = false;
  isPreparing: boolean = false;
  errorMessage: string = '';
  pendingMessage: string = '';
  checkout: ICheckoutResponse | null = null;
  displayTotal: string = '';
  displayCurrency: string = 'TRY';

  @Output() closePaymentComponentEvent = new EventEmitter<void>();
  @Output() paymentSuccess = new EventEmitter<void>();
  @Output() cartPrepared = new EventEmitter<void>();

  constructor(private restService: RestService) {}

  ngOnInit() {
    this.loadStripe();
    this.prepareCheckout();
  }

  async loadStripe() {
    // Initialize Stripe.js with your public key
    this.stripe = await loadStripe(
      'pk_test_51R5GK1RpCkckemuqxqwmtU3jtnARLIiSxsxaeU8lg7wQrJJH8oUxH5ZdykHQCRvFNvSL4duOLcL6XQY5Cwkxjcvp00VDagc07P'
    );

    // Create an instance of Elements and a card element
    this.elements = this.stripe.elements();
    this.card = this.elements.create('card');
    this.card.mount('#card-element');
  }

  // The server computes the immutable checkout snapshot. The client only
  // displays the returned total and never supplies prices, totals or currency.
  prepareCheckout() {
    if (this.isPreparing || this.isProcessing) {
      return;
    }
    this.isPreparing = true;
    this.restService.prepareCheckout().subscribe({
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
        this.errorMessage = 'Could not prepare the checkout. Please try again.';
      },
    });
  }

  async handlePayment() {
    // UI double-submit protection. The server-side payment-attempt lease and
    // idempotency key remain the authoritative control.
    if (this.isProcessing || !this.checkout) {
      return;
    }

    this.errorMessage = '';
    this.pendingMessage = '';
    this.isProcessing = true;

    const { token, error } = await this.stripe.createToken(this.card);

    if (error) {
      this.errorMessage = error.message;
      this.isProcessing = false;
      return;
    }

    this.processPayment(token);
  }

  processPayment(token: any) {
    const checkoutId = this.checkout!.checkoutId;

    this.restService.chargeCheckout(checkoutId, token.id).subscribe({
      next: (response) => {
        if (response.success && !response.pending) {
          this.completePayment();
        } else if (response.pending) {
          // The charge may already have succeeded; never start a new checkout.
          this.pollPaymentStatus(checkoutId, PaymentComponent.MAX_STATUS_POLLS);
        } else {
          this.isProcessing = false;
          this.errorMessage = 'Payment failed. Please try again.';
        }
      },
      // A network timeout is an unknown result: recover the existing checkout
      // instead of starting a new one.
      error: () =>
        this.pollPaymentStatus(checkoutId, PaymentComponent.MAX_STATUS_POLLS),
    });
  }

  /**
   * Polls the authenticated status endpoint for the SAME checkout. The server
   * retries order finalization on status access and returns the durable attempt
   * state, so an ambiguous network outcome can never cause a second charge.
   */
  pollPaymentStatus(checkoutId: string, remainingPolls: number) {
    this.restService.getPaymentStatus(checkoutId).subscribe({
      next: (status) => this.applyPaymentStatus(status, checkoutId, remainingPolls),
      error: () => {
        this.isProcessing = false;
        this.errorMessage =
          'Could not verify the payment status. Please check your orders before retrying.';
      },
    });
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
      this.pendingMessage =
        'Payment is being processed. Please wait, do not submit again.';
      setTimeout(
        () => this.pollPaymentStatus(checkoutId, remainingPolls - 1),
        PaymentComponent.STATUS_POLL_INTERVAL_MS,
      );
      return;
    }

    this.isProcessing = false;
    this.pendingMessage = '';

    if (status.pending) {
      this.errorMessage =
        'Payment is still being processed. Please check your orders before retrying.';
    } else if (status.refunded) {
      this.errorMessage = 'This payment was refunded.';
    } else if (status.state === 'FAILED_FINAL') {
      this.errorMessage = 'Payment failed. Please try again.';
    } else {
      this.errorMessage =
        'Payment status is unknown. Please check your orders before retrying.';
    }
  }

  private completePayment() {
    this.isProcessing = false;
    this.pendingMessage = '';
    data.totalCartPrice = 0;
    this.paymentSuccess.emit();
    this.closePaymentComponentEvent.emit();
    alert('Payment Successful!');
  }

  public closePaymentComponent() {
    this.closePaymentComponentEvent.emit();
  }
}
