import { Component, OnInit } from '@angular/core';
import { loadStripe } from '@stripe/stripe-js';

@Component({
  selector: 'app-payment',
  templateUrl: './payment.component.html',
  styleUrls: ['./payment.component.css'],
})
export class PaymentComponent implements OnInit {
  stripe: any;
  elements: any;
  card: any;

  ngOnInit() {
    this.loadStripe();
  }

  async loadStripe() {
    // Initialize Stripe.js
    this.stripe = await loadStripe('your-publishable-key-here');

    // Create an instance of Elements and a card element
    this.elements = this.stripe.elements();
    this.card = this.elements.create('card');
    this.card.mount('#card-element');
  }

  async handlePayment() {
    const { token, error } = await this.stripe.createToken(this.card);

    if (error) {
      console.error(error);
    } else {
      this.processPayment(token);
    }
  }

  // Call your backend API to create a charge
  processPayment(token: any) {
    // Call your Spring Boot backend to create the charge
    fetch('http://localhost:8080/payment/create-charge', {
      method: 'POST',
      body: JSON.stringify({
        token: token.id,
        amount: 5000, // Example amount in cents
      }),
      headers: {
        'Content-Type': 'application/json',
      },
    })
      .then((response) => response.json())
      .then((data) => {
        if (data.success) {
          alert('Payment Successful!');
        } else {
          alert('Payment Failed!');
        }
      });
  }
}
