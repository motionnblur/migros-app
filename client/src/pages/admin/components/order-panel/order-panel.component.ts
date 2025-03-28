import { Component } from '@angular/core';
import { MatPaginatorModule } from '@angular/material/paginator';
import { RestService } from '../../../../services/rest/rest.service';
import { IOrder } from '../../../../interfaces/IOrder';
import { CommonModule } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { ActionPanelComponent } from '../action-panel/action-panel.component';

export interface ITable {
  orderId: number;
  totalPrice: number;
  status: string;
  actions?: void;
}

@Component({
  selector: 'app-order-panel',
  imports: [
    MatPaginatorModule,
    CommonModule,
    MatTableModule,
    ActionPanelComponent,
  ],
  templateUrl: './order-panel.component.html',
  styleUrl: './order-panel.component.css',
})
export class OrderPanelComponent {
  displayedColumns: string[] = ['orderId', 'totalPrice', 'status', 'actions'];
  ordersData: IOrder[] = [];
  tableData: ITable[] = [];
  isOrderComponentOpen: boolean = false;
  isActionPanelOpen: boolean = false;
  dataSource = this.tableData;
  orderId: number = 0;
  constructor(private restService: RestService) {
    restService.getAllOrders(0, 5).subscribe((data: IOrder[]) => {
      this.tableData = [...data];
      this.dataSource = [...this.tableData];
    });
  }
  public openActionPanel(orderId: number) {
    this.orderId = orderId;
    this.isActionPanelOpen = true;
  }
  public closeActionPanel() {
    this.isActionPanelOpen = false;
  }
}
