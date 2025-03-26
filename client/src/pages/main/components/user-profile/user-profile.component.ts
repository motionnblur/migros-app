import { Component, EventEmitter, Output } from '@angular/core';

@Component({
  selector: 'app-user-profile',
  imports: [],
  templateUrl: './user-profile.component.html',
  styleUrl: './user-profile.component.css',
})
export class UserProfileComponent {
  @Output() closeComponentEvent = new EventEmitter<void>();
  public closeProfileComponent() {
    this.closeComponentEvent.emit();
  }
}
