import { Component } from '@angular/core';
import { MatPaginatorModule } from '@angular/material/paginator';
import { RestService } from '../../../../services/rest/rest.service';
import { IOrder } from '../../../../interfaces/IOrder';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-order-panel',
  imports: [MatPaginatorModule, CommonModule],
  templateUrl: './order-panel.component.html',
  styleUrl: './order-panel.component.css',
})
export class OrderPanelComponent {
  ordersData: IOrder[] = [];
  constructor(private restService: RestService) {
    restService.getAllOrders(0, 5).subscribe((data: IOrder[]) => {
      console.log(data);
      this.ordersData = data;
    });
  }
}
