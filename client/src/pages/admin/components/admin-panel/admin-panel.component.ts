import { Component } from '@angular/core';
import { ProductAdderComponent } from '../product-adder/product-adder.component';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-admin-panel',
  imports: [ProductAdderComponent, CommonModule],
  templateUrl: './admin-panel.component.html',
  styleUrl: './admin-panel.component.css',
})
export class AdminPanelComponent {
  hasProductAdderOpened = false;

  eventHandler(event: boolean) {
    if (event === true) {
      this.closeProductAdder();
    } else {
      this.openProductAdder();
    }
  }

  addProduct() {
    this.hasProductAdderOpened = !this.hasProductAdderOpened;
  }
  openProductAdder() {
    this.hasProductAdderOpened = true;
  }
  closeProductAdder() {
    this.hasProductAdderOpened = false;
  }
}
