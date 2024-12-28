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
  itemId!: number;
  hasProductAdderOpened = false;
  hasProductUpdaterOpened = false;
  private productChangedCallback!: (data: any) => void;

  constructor(private eventManager: EventService) {
    this.productChangedCallback = (data: any) => {
      this.productChangedEventHandler(data);
    };
  }

  productAddedEventHandler(event: boolean) {
    if (event === true) {
      this.closeProductAdder();
      this.closeProductUpdater();
    }
  }
  productChangedEventHandler(itemId: number) {
    this.itemId = itemId;
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

  ngOnInit(): void {
    this.eventManager.on('productChanged', this.productChangedCallback);
  }
  ngOnDestroy(): void {
    this.eventManager.off('productChanged', this.productChangedCallback);
  }
}
