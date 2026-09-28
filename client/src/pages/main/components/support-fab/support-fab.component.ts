import { Component, EventEmitter, Output } from '@angular/core';

@Component({
  selector: 'app-support-fab',
  standalone: true,
  templateUrl: './support-fab.component.html',
  styleUrl: './support-fab.component.css',
})
export class SupportFabComponent {
  @Output() supportRequested = new EventEmitter<void>();

  public openSupport(): void {
    this.supportRequested.emit();
  }
}
