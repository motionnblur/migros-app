import { Component } from '@angular/core';
import { ProductAdderComponent } from '../product-adder/product-adder.component';
import { CommonModule } from '@angular/common';
import { ProductBodyComponent } from '../product-body/product-body.component';
import { WallComponent } from '../wall/wall.component';
import { ProductUpdaterComponent } from '../product-adder/product-updater.component';
import { EventService } from '../../../../services/event/event.service';
@Component({
  selector: 'app-admin-panel',
  imports: [
    ProductAdderComponent,
    ProductBodyComponent,
    CommonModule,
    WallComponent,
    ProductUpdaterComponent,
  ],
  templateUrl: './admin-panel.component.html',
  styleUrl: './admin-panel.component.css',
})
export class AdminPanelComponent {
  productId!: number;
  hasProductAdderOpened: boolean = false;
  hasProductUpdaterOpened: boolean = false;
  private productChangedCallback!: (data: any) => void;

  constructor(private eventManager: EventService) {
    this.productChangedCallback = (data: any) => {
      this.productChangedEventHandler(data);
    };
  }
  ngOnInit(): void {
    this.eventManager.on('productChanged', this.productChangedCallback);
  }
  ngOnDestroy(): void {
    this.eventManager.off('productChanged', this.productChangedCallback);
  }

  productAddedEventHandler(event: boolean) {
    if (event === true) {
      this.closeProductAdder();
      this.closeProductUpdater();
    }
  }
  productChangedEventHandler(productId: number) {
    this.productId = productId;
    this.hasProductAdderOpened = false;
    this.hasProductUpdaterOpened = !this.hasProductUpdaterOpened;
  }
  hasWallOnClickedEventAdder(event: boolean) {
    if (event === true) {
      this.closeProductAdder();
      this.closeProductUpdater();
    }
  }
  addProduct() {
    this.hasProductAdderOpened = !this.hasProductAdderOpened;
  }
  openProductAdder() {
    this.hasProductUpdaterOpened = false;
    this.hasProductAdderOpened = true;
  }
  closeProductAdder() {
    this.hasProductAdderOpened = false;
  }
  closeProductUpdater() {
    this.hasProductUpdaterOpened = false;
  }
}
