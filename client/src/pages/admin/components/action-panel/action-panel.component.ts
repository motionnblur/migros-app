import {
  Component,
  EventEmitter,
  Input,
  Output,
  SimpleChanges,
  ViewChild,
} from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { IUserProfileTable } from '../../../../interfaces/IUserProfileTable';
import { MatFormFieldModule } from '@angular/material/form-field';
import { FormsModule } from '@angular/forms';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';

interface Status {
  value: string;
  viewValue: string;
}

@Component({
  selector: 'app-action-panel',
  imports: [
    MatFormFieldModule,
    MatSelectModule,
    MatInputModule,
    FormsModule,
    MatButtonModule,
  ],
  templateUrl: './action-panel.component.html',
  styleUrl: './action-panel.component.css',
})
export class ActionPanelComponent {
  @Input('orderId') orderId!: number;
  @Output() closeActionPanelEvent = new EventEmitter<void>();
  public userData!: IUserProfileTable;
  public status: Status[] = [
    { value: 'pending', viewValue: 'Pending' },
    { value: 'inProgress', viewValue: 'In Progress' },
    { value: 'completed', viewValue: 'Completed' },
  ];

  constructor(private restService: RestService) {}
  ngOnChanges(changes: SimpleChanges) {
    if (changes['orderId']) {
      this.restService
        .getUserProfileData(this.orderId)
        .subscribe((data: IUserProfileTable) => {
          this.userData = data;
        });
    }
  }

  public closeActionPanel() {
    this.closeActionPanelEvent.emit();
  }
}
