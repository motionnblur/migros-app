import { Component, Input } from '@angular/core';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { IProductUploader } from '../../../../interfaces/IProductUploader';
import { ProductAdderBase } from '../../../../base-components/product-adder.base';
import { RestService } from '../../../../services/rest/rest.service';
import { EventService } from '../../../../services/event/event.service';

@Component({
  selector: 'app-product-adder',
  imports: [CommonModule, FormsModule, ReactiveFormsModule],
  templateUrl: './product-adder.component.html',
  styleUrl: './product-adder.component.css',
})
export class ProductAdderComponent extends ProductAdderBase {
  override openEditor?(): void {
    throw new Error('Method not implemented.');
  }
  buttonString: string = 'Ekle';

  constructor(
    protected override restService: RestService,
    protected override eventManager: EventService
  ) {
    super(restService, eventManager);
    this.isUpdateMode = false;
  }

  uploadProductData() {
    if (!this.validateBeforeSubmit(true)) {
      return;
    }

    const productData: IProductUploader = {
      adminId: 1,
      productName: this.productName,
      subCategoryName: this.subCategoryName,
      productPrice: this.price,
      productCount: this.count,
      productDiscount: this.discount,
      productDescription: this.description,
      selectedImage: this.selectedImage,
      categoryValue: this.selectedFormValue!,
    };

    this.restService.uploadProductData(productData).subscribe({
      next: (status: boolean) => {
        if (status) {
          this.outProductAdded();
          this.eventManager.trigger('productAdded');
        }
      },
      error: (error) => {
        this.validationError =
          error?.error ?? 'Product could not be uploaded. Please check fields.';
      },
    });
  }
}
