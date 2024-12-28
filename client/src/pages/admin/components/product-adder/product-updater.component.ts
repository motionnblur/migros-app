import { Component, Input } from '@angular/core';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { ProductAdderComponent } from './product-adder.component';
import { RestService } from '../../../../services/rest/rest.service';
import { EventService } from '../../../../services/event/event.service';
import { IProductUpdater } from '../../../../interfaces/IProductUpdater';

@Component({
  selector: 'app-product-updater',
  imports: [CommonModule, FormsModule, ReactiveFormsModule],
  templateUrl: './product-adder.component.html',
  styleUrl: './product-adder.component.css',
})
export class ProductUpdaterComponent extends ProductAdderComponent {
  @Input() id!: number;
  constructor(
    override restService: RestService,
    override eventManager: EventService
  ) {
    super(restService, eventManager);
    this.buttonString = 'Update';
  }

  override uploadProductData(): void {
    const productData: IProductUpdater = {
      adminId: 1,
      productId: this.id,
      productName: this.productName,
      price: this.price,
      count: this.count,
      discount: this.discount,
      description: this.description,
      selectedImage: this.selectedImage,
      categoryValue: this.selectedFormValue,
    };
    this.restService
      .updateProductData(productData)
      .subscribe((status: boolean) => {
        if (status) {
          this.outProductAdded();
          this.eventManager.trigger('productAdded');
        }
      });
  }

  override ngOnInit(): void {
    super.ngOnInit();
    this.restService.getItemImage(this.id).subscribe((blob) => {
      const file = new File([this.selectedImage!], 'image.png', {
        type: 'image/png',
      });
      this.selectedImage = file;
      this.imageUrl = URL.createObjectURL(blob);
    });
    this.restService.getItemData(this.id).subscribe((data: any) => {
      this.productName = data.productName;
      this.price = data.productPrice;
      this.count = data.productCount;
      this.discount = data.productDiscount;
      this.description = data.productDescription;
      this.selectedFormValue = data.productCategoryId;

      this.categoryControl.setValue(this.selectedFormValue);
    });
  }
}
