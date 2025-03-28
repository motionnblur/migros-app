import { CommonModule } from '@angular/common';
import { Component, EventEmitter, OnInit, Output } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';

@Component({
  selector: 'app-order-tracker',
  imports: [CommonModule],
  templateUrl: './order-tracker.component.html',
  styleUrls: ['./order-tracker.component.css'],
})
export class OrderTrackerComponent {
  @Output() closeOrderTrackerComponentEvent = new EventEmitter<void>();

  currentStatus: 'Ordered' | 'Shipped' | 'Out for delivery' | 'Delivered' =
    'Ordered'; // You can dynamically set this value

  constructor() {}

  public closeOrderTrackerComponent() {
    this.closeOrderTrackerComponentEvent.emit();
  }
}
