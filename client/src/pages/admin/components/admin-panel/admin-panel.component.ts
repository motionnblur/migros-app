import { Component } from '@angular/core';
import { ProductAdderComponent } from '../product-adder/product-adder.component';
import { CommonModule } from '@angular/common';
import { ProductBodyComponent } from '../product-body/product-body.component';
import { WallComponent } from '../wall/wall.component';
import { ProductUpdaterComponent } from '../product-adder/product-updater.component';
import { EventService } from '../../../../services/event/event.service';
import { ProductEditComponent } from '../product-edit/product-edit.component';
@Component({
  selector: 'app-admin-panel',
  imports: [
    ProductAdderComponent,
    ProductBodyComponent,
    CommonModule,
    WallComponent,
    ProductUpdaterComponent,
    ProductEditComponent,
  ],
  templateUrl: './admin-panel.component.html',
  styleUrl: './admin-panel.component.css',
})
export class AdminPanelComponent {
  productId!: number;
  hasProductAdderOpened: boolean = false;
  hasProductsOpened: boolean = false;
  hasProductUpdaterOpened: boolean = false;
  hasProductEditOpened: boolean = false;
  private productChangedCallback!: (data: any) => void;
  private editorOpenedCallback!: (id: number) => void;

  constructor(private eventManager: EventService) {
    this.productChangedCallback = (data: any) => {
      this.productChangedEventHandler(data);
    };
    this.editorOpenedCallback = () => {
      this.editorOpenedEventHandler();
    };
  }
  ngOnInit(): void {
    this.eventManager.on('productChanged', this.productChangedCallback);
    this.eventManager.on('editorOpened', this.editorOpenedCallback);
  }
  ngOnDestroy(): void {
    this.eventManager.off('productChanged', this.productChangedCallback);
    this.eventManager.off('editorOpened', this.editorOpenedCallback);
  }

  productAddedEventHandler(event: boolean) {
    if (event === true) {
      this.closeProductAdder();
      this.closeProductUpdater();
    }
  }

  editorOpenedEventHandler() {
    this.hasProductEditOpened = !this.hasProductEditOpened;
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
      this.closeProductEdit();
    }
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
  closeProductEdit() {
    this.hasProductEditOpened = false;
  }

  open_close_Products() {
    this.hasProductsOpened = !this.hasProductsOpened;
  }
}
