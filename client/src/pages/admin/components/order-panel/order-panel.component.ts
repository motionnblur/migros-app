import {Component, OnInit} from '@angular/core';
import {MatPaginatorModule, PageEvent} from '@angular/material/paginator';
import {MatDialog} from '@angular/material/dialog';
import {RestService} from '../../../../services/rest/rest.service';
import {IOrderPage} from '../../../../interfaces/IOrderPage';
import {CommonModule} from '@angular/common';
import {ActionPanelComponent} from '../action-panel/action-panel.component';
import {orderStatusVariant} from '../../order-status';
import {ConfirmDialogComponent} from '../confirm-dialog/confirm-dialog.component';
import {ToastService} from '../../services/toast.service';

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
  readonly orderStatusVariant = orderStatusVariant;
  tableData: ITable[] = [];
  dataSource: ITable[] = [];
  isActionPanelOpen: boolean = false;
  orderId: number = 0;
  deletingOrderId: number | null = null;

  pageIndex = 0;
  pageSize = 5;
  totalOrders = 0;

  constructor(
    private restService: RestService,
    private dialog: MatDialog,
    private toastService: ToastService
  ) {
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
    this.dialog
      .open(ConfirmDialogComponent, {
        width: '420px',
        maxWidth: '92vw',
        autoFocus: false,
        panelClass: 'admin-confirm-dialog',
        data: {
          title: 'Siparişi sil',
          message: `Sipariş silinsin mi? (#${orderId})`,
          confirmText: 'Sil',
          danger: true,
        },
      })
      .afterClosed()
      .subscribe((approved) => {
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
              this.toastService.success('Sipariş silindi.');
            }
            this.deletingOrderId = null;
          },
          error: (err) => {
            console.error('Sipariş silinemedi', err);
            this.toastService.error('Sipariş silinemedi.');
            this.deletingOrderId = null;
          }
        });
      });
  }
}
