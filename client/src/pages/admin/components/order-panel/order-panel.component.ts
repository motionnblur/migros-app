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
      error: (err) => console.error('Siparişler yüklenemedi', err)
    });
  }

  public openActionPanel(orderId: number) {
    this.orderId = orderId;
    this.isActionPanelOpen = true;
  }

  public closeActionPanel() {
    this.isActionPanelOpen = false;
  }

  /**
   * Returns Bootstrap badge classes based on order status
   */
  getStatusClass(status: string): string {
    const s = status.toLowerCase();
    if (s.includes('hazır') || s.includes('tamam')) return 'bg-success text-white';
    if (s.includes('yolda') || s.includes('bekliyor')) return 'bg-warning text-dark';
    if (s.includes('iptal')) return 'bg-danger text-white';
    return 'bg-secondary text-white';
  }
}
