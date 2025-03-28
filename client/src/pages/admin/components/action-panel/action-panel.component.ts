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

@Component({
  selector: 'app-action-panel',
  imports: [],
  templateUrl: './action-panel.component.html',
  styleUrl: './action-panel.component.css',
})
export class ActionPanelComponent {
  @Input('orderId') orderId!: number;
  @Output() closeActionPanelEvent = new EventEmitter<void>();

  constructor(private restService: RestService) {}
  ngOnChanges(changes: SimpleChanges) {
    if (changes['orderId']) {
      this.restService
        .getUserProfileData(this.orderId)
        .subscribe((data: IUserProfileTable) => {
          console.log(data);
        });
    }
  }

  public closeActionPanel() {
    this.closeActionPanelEvent.emit();
  }
}
