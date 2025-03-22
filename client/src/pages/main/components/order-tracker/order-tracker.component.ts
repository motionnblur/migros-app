import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';

@Component({
  selector: 'app-order-tracker',
  imports: [CommonModule],
  templateUrl: './order-tracker.component.html',
  styleUrls: ['./order-tracker.component.css'],
})
export class OrderTrackerComponent implements OnInit {
  currentStatus: 'Ordered' | 'Shipped' | 'Out for delivery' | 'Delivered' =
    'Ordered'; // You can dynamically set this value

  constructor() {}

  ngOnInit(): void {
    // In a real application, you would fetch the order status from an API
    // For now, we're hardcoding it based on the image you provided.
  }
}
