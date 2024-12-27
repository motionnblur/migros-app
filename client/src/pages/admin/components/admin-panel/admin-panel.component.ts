import { Component } from '@angular/core';
import { ProductAdderComponent } from '../product-adder/product-adder.component';
import { CommonModule } from '@angular/common';
import { ProductBodyComponent } from '../product-body/product-body.component';
import { WallComponent } from '../wall/wall.component';
@Component({
  selector: 'app-admin-panel',
  imports: [
    ProductAdderComponent,
    ProductBodyComponent,
    CommonModule,
    WallComponent,
  ],
  templateUrl: './admin-panel.component.html',
  styleUrl: './admin-panel.component.css',
})
export class AdminPanelComponent {
  hasProductAdderOpened = false;

  productAddedEventHandler(event: boolean) {
    if (event === true) {
      this.closeProductAdder();
    } else {
      this.openProductAdder();
    }
  }
  hasWallOnClickedEventAdder(event: boolean) {
    if (event === true) {
      this.closeProductAdder();
    }
  }
  productBodyClickedEventHandler() {
    console.log('clicked');
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
