import {Component, OnInit} from '@angular/core';
import {MatPaginatorModule} from '@angular/material/paginator';
import {RestService} from '../../../../services/rest/rest.service';
import {IOrder} from '../../../../interfaces/IOrder';
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

  constructor(private restService: RestService) {
  }

  ngOnInit() {
    this.loadOrders();
  }

  loadOrders() {
    this.restService.getAllOrders(0, 10).subscribe({
      next: (data: IOrder[]) => {
        this.tableData = data;
        this.dataSource = data;
      },
      error: (err) => console.error('Siparisler yuklenemedi', err)
    });
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
