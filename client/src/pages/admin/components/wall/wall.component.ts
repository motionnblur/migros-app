import { Component, EventEmitter, Output } from '@angular/core';

@Component({
  selector: 'app-wall',
  imports: [],
  templateUrl: './wall.component.html',
  styleUrl: './wall.component.css',
})
export class WallComponent {
  @Output() hasWallOnClicked = new EventEmitter<boolean>();

  onClick() {
    this.hasWallOnClicked.emit(true);
  }
}
