import {Component, EventEmitter, Input, Output, OnChanges, SimpleChanges} from '@angular/core';
import {CommonModule} from '@angular/common'; // Use CommonModule for *ngFor and *ngIf
import {FormsModule} from '@angular/forms';
import {RestService} from '../../../../services/rest/rest.service';
import {IUserProfileTable} from '../../../../interfaces/IUserProfileTable';

interface Status {
  value: string;
  viewValue: string;
}

@Component({
  selector: 'app-action-panel',
  standalone: true,
  imports: [CommonModule, FormsModule], // Cleaned up Material imports
  templateUrl: './action-panel.component.html',
  styleUrl: './action-panel.component.css',
})
export class ActionPanelComponent implements OnChanges {
  @Input() orderId!: number;
  @Output() closeActionPanelEvent = new EventEmitter<void>();

  public userData!: IUserProfileTable;
  public selectedStatus: string = '';

  public status: Status[] = [
    {value: 'Ordered', viewValue: 'Sipariş Alındı'},
    {value: 'Shipped', viewValue: 'Kargoya Verildi'},
    {value: 'Out for delivery', viewValue: 'Dağıtımda'},
    {value: 'Delivered', viewValue: 'Teslim Edildi'},
  ];

  constructor(private restService: RestService) {
  }

  ngOnChanges(changes: SimpleChanges) {
    if (changes['orderId'] && this.orderId) {
      this.restService.getUserProfileData(this.orderId).subscribe({
        next: (data: IUserProfileTable) => {
          this.userData = data;
        },
        error: (err) => console.error('Error fetching user profile', err)
      });
    }
  }

  public closeActionPanel() {
    this.closeActionPanelEvent.emit();
  }

  public save() {
    if (!this.selectedStatus) return;

    this.restService.updateOrderStatus(this.orderId, this.selectedStatus).subscribe({
      next: (success: boolean) => {
        if (success) {
          // You could use a toast here later!
          alert('Sipariş durumu güncellendi: ' + this.selectedStatus);
          this.closeActionPanel();
        }
      },
      error: (err) => console.error('Update failed', err)
    });
  }
}
