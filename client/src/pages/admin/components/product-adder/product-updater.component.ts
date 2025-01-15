import { Component, Input } from '@angular/core';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { IProductUpdater } from '../../../../interfaces/IProductUpdater';
import { ProductAdderBase } from '../../../../base-components/product-adder.base';
import { RestService } from '../../../../services/rest/rest.service';
import { EventService } from '../../../../services/event/event.service';

@Component({
  selector: 'app-product-updater',
  imports: [CommonModule, FormsModule, ReactiveFormsModule],
  templateUrl: './product-adder.component.html',
  styleUrl: './product-adder.component.css',
})
export class ProductUpdaterComponent extends ProductAdderBase {
  @Input() id!: number;
  buttonString: string = 'Update';

  constructor(
    protected override restService: RestService,
    protected override eventManager: EventService
  ) {
    super(restService, eventManager);
    this.isUpdateMode = true;
  }

  override ngOnInit(): void {
    super.ngOnInit();
    this.restService.getProductImage(this.id).subscribe((blob) => {
      const file = new File([this.selectedImage!], 'image.png', {
        type: 'image/png',
      });
      this.selectedImage = file;
      this.imageUrl = URL.createObjectURL(blob);
    });
    this.restService.getProductData(this.id).subscribe((data: any) => {
      this.productName = data.productName;
      this.subCategoryName = data.subCategoryName;
      this.price = data.productPrice;
      this.count = data.productCount;
      this.discount = data.productDiscount;
      this.description = data.productDescription;
      this.selectedFormValue = data.productCategoryId;

      this.categoryControl.setValue(this.selectedFormValue);
    });
  }

  uploadProductData(): void {
    const productData: IProductUpdater = {
      adminId: 1,
      productId: this.id,
      productName: this.productName,
      subCategoryName: this.subCategoryName,
      productPrice: this.price,
      productCount: this.count,
      productDiscount: this.discount,
      productDescription: this.description,
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
  override openEditor() {
    this.eventManager.trigger('editorOpened');
  }
}
