import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { RestService } from '../../../../services/rest/rest.service';
import { ScrollingModule } from '@angular/cdk/scrolling';

@Component({
  selector: 'app-order-tracker',
  imports: [CommonModule, ScrollingModule],
  templateUrl: './order-tracker.component.html',
  styleUrls: ['./order-tracker.component.css'],
})
export class OrderTrackerComponent {
  @Output() closeOrderTrackerComponentEvent = new EventEmitter<void>();
  @Input('orderIds') orderIds!: number[];
  public showOrderTrackerComponent = false;
  currentSelectedOrderId: number = 0;

  currentStatus: 'Ordered' | 'Shipped' | 'Out for delivery' | 'Delivered' =
    'Ordered'; // You can dynamically set this value

  constructor(private restService: RestService) {
    restService.getAllOrderIds().subscribe({
      next: (data: number[]) => {
        this.orderIds = data;
      },
      error: (error: any) => {
        console.error(error);
      },
    });
  }

  public closeOrderTrackerComponent() {
    this.closeOrderTrackerComponentEvent.emit();
  }
  public openOrderTrackerComponent(orderId: number) {
    this.showOrderTrackerComponent = true;
    this.restService.getOrderStatusByOrderId(orderId).subscribe({
      next: (data: string) => {
        switch (data) {
          case 'Ordered':
            this.currentStatus = 'Ordered';
            break;
          case 'Shipped':
            this.currentStatus = 'Shipped';
            break;
          case 'Out for delivery':
            this.currentStatus = 'Out for delivery';
            break;
          case 'Delivered':
            this.currentStatus = 'Delivered';
            break;
        }
        this.currentSelectedOrderId = orderId;
      },
      error: (error: any) => {
        console.error(error);
      },
    });
  }
  public closeOrderTrackerAnim() {
    this.showOrderTrackerComponent = false;
  }
  public cancelOrder() {
    if (this.orderIds.length == 0) return;

    this.restService.calcelOrder(this.currentSelectedOrderId).subscribe({
      next: (data: boolean) => {
        if (data) {
          this.closeOrderTrackerComponent();
        }
      },
      error: (error: any) => {
        console.error(error);
      },
    });
  }
}
