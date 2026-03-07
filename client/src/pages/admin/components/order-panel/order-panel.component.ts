import {Component, OnInit} from '@angular/core';
import {MatPaginatorModule, PageEvent} from '@angular/material/paginator';
import {RestService} from '../../../../services/rest/rest.service';
import {IOrderPage} from '../../../../interfaces/IOrderPage';
import {CommonModule} from '@angular/common';
import {ActionPanelComponent} from '../action-panel/action-panel.component';

export interface ITable {
  orderId: number;
  totalPrice: number;
  status: string;
}

@Component({
  selector: 'app-order-panel',
  standalone: true,
  imports: [
    MatPaginatorModule,
    CommonModule,
    ActionPanelComponent,
  ],
  templateUrl: './order-panel.component.html',
  styleUrl: './order-panel.component.css',
})
export class OrderPanelComponent implements OnInit {
  tableData: ITable[] = [];
  dataSource: ITable[] = [];
  isActionPanelOpen: boolean = false;
  orderId: number = 0;
  deletingOrderId: number | null = null;

  pageIndex = 0;
  pageSize = 5;
  totalOrders = 0;

  constructor(private restService: RestService) {
  }

  ngOnInit() {
    this.loadOrders();
  }

  loadOrders(pageIndex: number = this.pageIndex, pageSize: number = this.pageSize) {
    this.restService.getAllOrders(pageIndex, pageSize).subscribe({
      next: (page: IOrderPage) => {
        this.tableData = page.items;
        this.dataSource = page.items;
        this.totalOrders = page.total;
      },
      error: (err) => console.error('Siparisler yuklenemedi', err)
    });
  }

  onPageChange(event: PageEvent) {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.loadOrders(this.pageIndex, this.pageSize);
  }

  public openActionPanel(orderId: number) {
    this.orderId = orderId;
    this.isActionPanelOpen = true;
  }

  public closeActionPanel() {
    this.isActionPanelOpen = false;
  }

  public deleteOrder(orderId: number) {
    const approved = confirm(`Siparis silinsin mi? (#${orderId})`);
    if (!approved) {
      return;
    }

    this.deletingOrderId = orderId;
    this.restService.deleteOrder(orderId).subscribe({
      next: (success) => {
        if (success) {
          this.dataSource = this.dataSource.filter((item) => item.orderId !== orderId);
          this.tableData = this.tableData.filter((item) => item.orderId !== orderId);
          this.totalOrders = Math.max(0, this.totalOrders - 1);
        }
        this.deletingOrderId = null;
      },
      error: (err) => {
        console.error('Siparis silinemedi', err);
        this.deletingOrderId = null;
      }
    });
  }

  /**
   * Returns Bootstrap badge classes based on order status
   */
  getStatusClass(status: string): string {
    const s = status.toLowerCase();
    if (s.includes('hazir') || s.includes('tamam')) return 'bg-success text-white';
    if (s.includes('yolda') || s.includes('bekliyor')) return 'bg-warning text-dark';
    if (s.includes('iptal')) return 'bg-danger text-white';
    return 'bg-secondary text-white';
  }
}
