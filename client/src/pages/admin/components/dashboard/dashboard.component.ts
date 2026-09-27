import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin, of, Subscription } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { IOrder } from '../../../../interfaces/IOrder';
import { IOrderPage } from '../../../../interfaces/IOrderPage';
import { categories } from '../../../../memory/global-data';
import { RestService } from '../../../../services/rest/rest.service';
import { orderStatusVariant } from '../../order-status';

interface DashboardStat {
  key: string;
  label: string;
  value: number;
  icon: string;
  tone: 'brand' | 'info' | 'danger' | 'success';
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.css',
})
export class DashboardComponent implements OnInit, OnDestroy {
  readonly orderStatusVariant = orderStatusVariant;

  stats: DashboardStat[] = [];
  recentOrders: IOrder[] = [];
  isStatsLoading = true;
  isOrdersLoading = true;
  statsError = '';
  ordersError = '';

  private ordersSub: Subscription | null = null;
  private supportSub: Subscription | null = null;
  private productsSub: Subscription | null = null;

  constructor(private readonly restService: RestService) {}

  trackByKey(_index: number, stat: DashboardStat): string {
    return stat.key;
  }

  ngOnInit(): void {
    this.loadOrders();
    this.loadSupportStats();
    this.loadProductTotal();
  }

  ngOnDestroy(): void {
    this.ordersSub?.unsubscribe();
    this.supportSub?.unsubscribe();
    this.productsSub?.unsubscribe();
  }

  private loadOrders(): void {
    this.isOrdersLoading = true;
    this.ordersError = '';

    this.ordersSub = this.restService.getAllOrders(0, 5).subscribe({
      next: (page: IOrderPage) => {
        this.recentOrders = page?.items ?? [];
        this.totalOrdersValue = page?.total ?? 0;
        this.isOrdersLoading = false;
        this.rebuildStats();
      },
      error: () => {
        this.ordersError = 'Siparişler yüklenemedi.';
        this.isOrdersLoading = false;
        this.totalOrdersValue = 0;
        this.rebuildStats();
      },
    });
  }

  private loadSupportStats(): void {
    this.supportSub = forkJoin({
      pending: this.restService
        .getSupportUsersForAdmin()
        .pipe(catchError(() => of([] as string[]))),
      banned: this.restService
        .getBannedSupportUsersForAdmin()
        .pipe(catchError(() => of([] as string[]))),
    }).subscribe(({ pending, banned }) => {
      this.pendingChatValue = pending?.length ?? 0;
      this.bannedUserValue = banned?.length ?? 0;
      this.rebuildStats();
    });
  }

  private loadProductTotal(): void {
    const requests = categories.map((category) =>
      this.restService.getProductCountsFromCategoryAdmin(category.value).pipe(
        map((count) => Number(count) || 0),
        catchError(() => of(0))
      )
    );

    this.productsSub = forkJoin(requests).subscribe((counts) => {
      this.totalProductValue = counts.reduce((sum, count) => sum + count, 0);
      this.isStatsLoading = false;
      this.rebuildStats();
    });
  }

  private totalOrdersValue = 0;
  private pendingChatValue = 0;
  private bannedUserValue = 0;
  private totalProductValue = 0;

  private rebuildStats(): void {
    this.stats = [
      {
        key: 'orders',
        label: 'Toplam Sipariş',
        value: this.totalOrdersValue,
        icon: 'bi-receipt',
        tone: 'brand',
      },
      {
        key: 'pending',
        label: 'Bekleyen Sohbet',
        value: this.pendingChatValue,
        icon: 'bi-chat-dots',
        tone: 'info',
      },
      {
        key: 'banned',
        label: 'Banlı Kullanıcı',
        value: this.bannedUserValue,
        icon: 'bi-person-slash',
        tone: 'danger',
      },
      {
        key: 'products',
        label: 'Toplam Ürün',
        value: this.totalProductValue,
        icon: 'bi-box-seam',
        tone: 'success',
      },
    ];
  }
}
