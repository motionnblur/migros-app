import { Component } from '@angular/core';
import { MatPaginatorModule } from '@angular/material/paginator';
import { RestService } from '../../../../services/rest/rest.service';
import { IOrder } from '../../../../interfaces/IOrder';
import { CommonModule } from '@angular/common';
import { MatTableModule } from '@angular/material/table';

export interface PeriodicElement {
  orderId: number;
  totalPrice: number;
  status: string;
}
const ELEMENT_DATA: PeriodicElement[] = [
  {
    orderId: 1,
    totalPrice: 100,
    status: 'pending',
  },
  {
    orderId: 2,
    totalPrice: 200,
    status: 'pending',
  },
  {
    orderId: 3,
    totalPrice: 300,
    status: 'pending',
  },
  {
    orderId: 4,
    totalPrice: 400,
    status: 'pending',
  },
  {
    orderId: 5,
    totalPrice: 500,
    status: 'pending',
  },
];

@Component({
  selector: 'app-order-panel',
  imports: [MatPaginatorModule, CommonModule, MatTableModule],
  templateUrl: './order-panel.component.html',
  styleUrl: './order-panel.component.css',
})
export class OrderPanelComponent {
  displayedColumns: string[] = ['orderId', 'totalPrice', 'status'];
  dataSource = ELEMENT_DATA;
  ordersData: IOrder[] = [];
  isOrderComponentOpen: boolean = false;
  constructor(private restService: RestService) {
    restService.getAllOrders(0, 5).subscribe((data: IOrder[]) => {
      console.log(data);
      this.ordersData = data;
    });
  }
  public openOrderComponent(orderId: number) {
    this.isOrderComponentOpen = true;
    /* this.restService.getOrder(orderId).subscribe((data: any) => {
      console.log(data);
    }); */
  }
}
