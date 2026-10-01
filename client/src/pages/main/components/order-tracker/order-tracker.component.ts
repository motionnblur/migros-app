import { Component, EventEmitter, Input, Output, HostListener } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ScrollingModule } from '@angular/cdk/scrolling';
import { RestService } from '../../../../services/rest/rest.service';
import { ActivatedRoute, Router } from '@angular/router';

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
  public cancellationError = '';
  public isCancelling = false;

  private statusSteps = ['Ordered', 'Shipped', 'Out for delivery', 'Delivered'];

  public get canCancelOrder(): boolean {
    return this.currentStatus.trim().toLowerCase() === 'pending';
  }

  constructor(
    private restService: RestService,
    private router: Router,
    private route: ActivatedRoute
  ) {
    this.restService.getAllOrderIds().subscribe({
      next: (data) => (this.orderIds = data),
      error: (err) => console.error(err),
    });
  }

  @HostListener('document:keydown.escape')
  onEsc() {
    this.closeOrderTrackerComponent();
  }

  public getStatusClass(stepName: string): string {
    const currentStatus = this.canCancelOrder ? 'Ordered' : this.currentStatus;
    const currentIdx = this.statusSteps.indexOf(currentStatus);
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
        this.cancellationError = '';
        this.showOrderTrackerComponent = true;
      },
    });
  }

  public closeOrderTrackerAnim() {
    this.showOrderTrackerComponent = false;
  }

  public closeOrderTrackerComponent() {
    this.router.navigate([{ outlets: { modal: null } }], {
      relativeTo: this.route.parent ?? this.route,
    });
    this.closeOrderTrackerComponentEvent.emit();
  }

  public cancelOrder() {
    if (this.currentSelectedOrderId === 0 || !this.canCancelOrder || this.isCancelling) return;
    this.isCancelling = true;
    this.cancellationError = '';
    this.restService.calcelOrder(this.currentSelectedOrderId).subscribe({
      next: (success) => {
        this.isCancelling = false;
        if (success) {
          alert('Sipariş iptal edildi.');
          this.closeOrderTrackerComponent();
        }
      },
      error: () => {
        this.isCancelling = false;
        this.cancellationError = 'Sipariş iptal edilemedi. Sipariş durumunu kontrol edip tekrar deneyin.';
        this.restService.getOrderStatusByOrderId(this.currentSelectedOrderId).subscribe({
          next: (status) => {
            this.currentStatus = status;
            if (!this.canCancelOrder) this.cancellationError = '';
          },
        });
      },
    });
  }
}
