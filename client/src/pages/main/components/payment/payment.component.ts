import { Component, EventEmitter, OnInit, Output } from '@angular/core';
import { loadStripe } from '@stripe/stripe-js';
import { data } from '../../../../memory/global-data';
import { RestService } from '../../../../services/rest/rest.service';
import { ICheckoutResponse } from '../../../../interfaces/ICheckoutResponse';

@Component({
  selector: 'app-payment',
  templateUrl: './payment.component.html',
  styleUrls: ['./payment.component.css'],
})
export class PaymentComponent implements OnInit {
  stripe: any;
  elements: any;
  card: any;
  isProcessing: boolean = false;
  isPreparing: boolean = false;
  errorMessage: string = '';
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
    if (this.isProcessing || !this.checkout) {
      return;
    }

    this.errorMessage = '';
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
          this.recoverCheckoutStatus(checkoutId);
        } else {
          this.isProcessing = false;
          this.errorMessage = 'Payment failed. Please try again.';
        }
      },
      // A network timeout is an unknown result: recover the existing checkout
      // instead of starting a new one.
      error: () => this.recoverCheckoutStatus(checkoutId),
    });
  }

  recoverCheckoutStatus(checkoutId: string) {
    this.restService.getCheckoutStatus(checkoutId).subscribe({
      next: (status) => {
        this.isProcessing = false;
        if (status.status === 'CONSUMED') {
          this.completePayment();
        } else if (status.status === 'PAID') {
          this.errorMessage =
            'Payment received and is being finalized. Please check your orders.';
        } else if (status.status === 'CANCELLED' || status.status === 'EXPIRED') {
          this.errorMessage = 'Payment failed. Please try again.';
        } else {
          this.errorMessage =
            'Payment status is unknown. Please check your orders before retrying.';
        }
      },
      error: () => {
        this.isProcessing = false;
        this.errorMessage =
          'Could not verify the payment status. Please check your orders.';
      },
    });
  }

  private completePayment() {
    this.isProcessing = false;
    data.totalCartPrice = 0;
    this.paymentSuccess.emit();
    this.closePaymentComponentEvent.emit();
    alert('Payment Successful!');
  }

  public closePaymentComponent() {
    this.closePaymentComponentEvent.emit();
  }
}
