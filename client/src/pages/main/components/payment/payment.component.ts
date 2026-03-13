import { Component, EventEmitter, OnInit, Output } from '@angular/core';
import { loadStripe } from '@stripe/stripe-js';
import { data } from '../../../../memory/global-data';

const API_BASE_URL = 'https://migros-app.onrender.com';

@Component({
  selector: 'app-payment',
  templateUrl: './payment.component.html',
  styleUrls: ['./payment.component.css'],
})
export class PaymentComponent implements OnInit {
  stripe: any;
  elements: any;
  card: any;
  isProcessing: boolean = false; // To track payment process
  errorMessage: string = ''; // To store and display errors

  @Output() closePaymentComponentEvent = new EventEmitter<void>();
  @Output() paymentSuccess = new EventEmitter<void>();

  ngOnInit() {
    this.loadStripe();
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

  async handlePayment() {
    // Clear previous errors
    this.errorMessage = '';
    this.isProcessing = true; // Indicate payment is in progress

    // Create token with Stripe
    const { token, error } = await this.stripe.createToken(this.card);

    if (error) {
      this.errorMessage = error.message; // Display error message
      this.isProcessing = false; // Reset processing state
    } else {
      this.processPayment(token);
    }
  }

  // Call your backend API to create a charge
  processPayment(token: any) {
    fetch(`${API_BASE_URL}/payment/create-charge`, {
      method: 'POST',
      body: JSON.stringify({
        token: token.id,
      }),
      headers: {
        'Content-Type': 'application/json',
      },
      credentials: 'include',
    })
      .then((response) => response.json())
      .then((data) => {
        console.log(data);
        this.isProcessing = false; // Reset processing state

        if (data.success) {
          data.totalCartPrice = 0;
          this.paymentSuccess.emit();
          this.closePaymentComponentEvent.emit();
          alert('Payment Successful!');
        } else {
          alert('Payment Failed! Please try again.');
        }
      })
      .catch((error) => {
        this.isProcessing = false; // Reset processing state
        this.errorMessage =
          'Payment failed due to a network issue. Please try again.';
      });
  }

  public closePaymentComponent() {
    this.closePaymentComponentEvent.emit();
  }
}
