import {Component, EventEmitter, Input, Output, HostListener} from '@angular/core';
import {CommonModule} from '@angular/common';
import {ScrollingModule} from '@angular/cdk/scrolling';
import {RestService} from '../../../../services/rest/rest.service';

@Component({
  selector: 'app-order-tracker',
  standalone: true,
  imports: [CommonModule, ScrollingModule],
  templateUrl: './order-tracker.component.html',
  styleUrls: ['./order-tracker.component.css'],
})
export class OrderTrackerComponent {
  @Output() closeOrderTrackerComponentEvent = new EventEmitter<void>();
  @Input('orderIds') orderIds: number[] = [];

  public showOrderTrackerComponent = false;
  public currentSelectedOrderId: number = 0;
  public currentStatus: string = 'Ordered';

  // Status order for calculation
  private statusSteps = ['Ordered', 'Shipped', 'Out for delivery', 'Delivered'];

  constructor(private restService: RestService) {
    this.restService.getAllOrderIds().subscribe({
      next: (data) => this.orderIds = data,
      error: (err) => console.error(err)
    });
  }

  @HostListener('document:keydown.escape')
  onEsc() {
    this.closeOrderTrackerComponent();
  }

  public getStatusClass(stepName: string): string {
    const currentIdx = this.statusSteps.indexOf(this.currentStatus);
    const stepIdx = this.statusSteps.indexOf(stepName);

    if (stepIdx < currentIdx) return 'completed';
    if (stepIdx === currentIdx) return 'active';
    return '';
  }

  public openOrderTrackerComponent(orderId: number) {
    this.restService.getOrderStatusByOrderId(orderId).subscribe({
      next: (status) => {
        this.currentStatus = status;
        this.currentSelectedOrderId = orderId;
        this.showOrderTrackerComponent = true;
      }
    });
  }

  public closeOrderTrackerAnim() {
    this.showOrderTrackerComponent = false;
  }

  public closeOrderTrackerComponent() {
    this.closeOrderTrackerComponentEvent.emit();
  }

  public cancelOrder() {
    if (this.currentSelectedOrderId === 0) return;
    this.restService.calcelOrder(this.currentSelectedOrderId).subscribe({
      next: (success) => {
        if (success) {
          alert('Sipariş iptal edildi.');
          this.closeOrderTrackerComponent();
        }
      }
    });
  }
}
