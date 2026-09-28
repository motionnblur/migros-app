import { Component, EventEmitter, Output, HostListener, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subject, takeUntil } from 'rxjs';
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
export class OrderHistoryComponent implements OnDestroy {
  @Output() closeOrderHistoryComponentEvent = new EventEmitter<void>();

  orderGroups: IUserOrderGroup[] = [];
  selectedGroup: IUserOrderGroup | null = null;
  isLoading = false;
  errorMessage = '';
  private readonly destroy$ = new Subject<void>();
  private readonly objectUrls = new Set<string>();

  constructor(
    private restService: RestService,
    private router: Router,
    private route: ActivatedRoute
  ) {
    this.loadOrderGroups();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.objectUrls.forEach((url) => window.URL.revokeObjectURL(url));
    this.objectUrls.clear();
  }

  @HostListener('document:keydown.escape')
  onEsc() {
    this.closeOrderHistoryComponent();
  }

  private loadOrderGroups() {
    this.isLoading = true;
    this.errorMessage = '';

    this.restService.getUserOrderGroups().pipe(takeUntil(this.destroy$)).subscribe({
      next: (groups: IUserOrderGroup[]) => {
        this.orderGroups = groups;
        this.isLoading = false;

        this.orderGroups.forEach((group) => {
          group.items.forEach((item) => {
            this.restService.getProductImage(item.productId).pipe(takeUntil(this.destroy$)).subscribe({
              next: (blob: Blob) => {
                const url: string = window.URL.createObjectURL(blob);
                this.objectUrls.add(url);
                item.productImageUrl = url;
              },
            });
          });
        });
      },
      error: () => {
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
