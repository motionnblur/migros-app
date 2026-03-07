import { Component, EventEmitter, Output, HostListener } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RestService } from '../../../../services/rest/rest.service';
import { IUserOrderGroup } from '../../../../interfaces/IUserOrderGroup';
import { ActivatedRoute, Router } from '@angular/router';

@Component({
  selector: 'app-order-history',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './order-history.component.html',
  styleUrls: ['./order-history.component.css'],
})
export class OrderHistoryComponent {
  @Output() closeOrderHistoryComponentEvent = new EventEmitter<void>();

  orderGroups: IUserOrderGroup[] = [];
  selectedGroup: IUserOrderGroup | null = null;
  isLoading = false;
  errorMessage = '';

  constructor(
    private restService: RestService,
    private router: Router,
    private route: ActivatedRoute
  ) {
    this.loadOrderGroups();
  }

  @HostListener('document:keydown.escape')
  onEsc() {
    this.closeOrderHistoryComponent();
  }

  private loadOrderGroups() {
    this.isLoading = true;
    this.errorMessage = '';

    this.restService.getUserOrderGroups().subscribe({
      next: (groups: IUserOrderGroup[]) => {
        this.orderGroups = groups;
        this.isLoading = false;

        this.orderGroups.forEach((group) => {
          group.items.forEach((item) => {
            this.restService.getProductImage(item.productId).subscribe({
              next: (blob: Blob) => {
                const url: string = window.URL.createObjectURL(blob);
                item.productImageUrl = url;
              },
            });
          });
        });
      },
      error: (err: any) => {
        console.error(err);
        this.isLoading = false;
        this.errorMessage = 'Siparisler yuklenemedi.';
      },
    });
  }

  public selectGroup(group: IUserOrderGroup) {
    this.selectedGroup = group;
  }

  public clearSelection() {
    this.selectedGroup = null;
  }

  public closeOrderHistoryComponent() {
    this.router.navigate([{ outlets: { modal: null } }], {
      relativeTo: this.route.parent ?? this.route,
    });
    this.closeOrderHistoryComponentEvent.emit();
  }

  public getGroupSummary(group: IUserOrderGroup): string {
    const itemCount = group.items.reduce((sum, item) => sum + item.count, 0);
    return `${itemCount} urun`;
  }
}
