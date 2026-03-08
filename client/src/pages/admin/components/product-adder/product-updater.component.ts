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
  buttonString: string = 'Güncelle';
  isImageChanged: boolean = false;

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
      const file = new File([blob], 'image.png', {
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

      this.categoryControl.setValue(this.selectedFormValue?.toString() ?? '');
    });
  }

  override onImageSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.isImageChanged = true;
      super.onImageSelected(event);
    }
  }

  override openEditor() {
    this.eventManager.trigger('editorOpened');
  }

  uploadProductData(): void {
    if (!this.validateBeforeSubmit(false)) {
      return;
    }

    const productData: IProductUpdater = {
      adminId: 1,
      productId: this.id,
      productName: this.productName,
      subCategoryName: this.subCategoryName,
      productPrice: this.price,
      productCount: this.count,
      productDiscount: this.discount,
      productDescription: this.description,
      selectedImage: this.isImageChanged ? this.selectedImage : undefined,
      categoryValue: this.selectedFormValue!,
    };

    this.restService.updateProductData(productData).subscribe({
      next: (status: boolean) => {
        if (status) {
          this.outProductAdded();
          this.eventManager.trigger('productAdded');
        }
      },
      error: (error) => {
        this.validationError =
          error?.error ?? 'Product could not be updated. Please check fields.';
      },
    });
  }
}

