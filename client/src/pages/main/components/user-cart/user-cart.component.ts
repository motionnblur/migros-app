import { Component, EventEmitter, Output } from '@angular/core';

@Component({
  selector: 'app-user-cart',
  imports: [],
  templateUrl: './user-cart.component.html',
  styleUrl: './user-cart.component.css',
})
export class UserCartComponent {
  @Output() closeComponentEvent = new EventEmitter<void>();
  constructor() {}
  ngAfterViewInit() {
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') {
        this.closeCartComponent();
      }
    });
  }
  ngOnDestroy() {
    document.removeEventListener('keydown', (event) => {
      if (event.key === 'Escape') {
        this.closeCartComponent();
      }
    });
  }
  public closeCartComponent() {
    this.closeComponentEvent.emit();
  }
}
