import { Component, Input } from '@angular/core';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { IProductData } from '../../../../interfaces/IProductData';
import { IProductUpdater } from '../../../../interfaces/IProductUpdater';
import { ProductPackageUnit } from '../../../../interfaces/IProductPackageMetadata';
import { ProductAdderBase } from '../../../../base-components/product-adder.base';
import { RestService } from '../../../../services/rest/rest.service';
import { EventService } from '../../../../services/event/event.service';
import { isProductEditConflict } from '../../../../services/rest/product-edit-conflict';

@Component({
  selector: 'app-product-updater',
  imports: [CommonModule, FormsModule, ReactiveFormsModule],
  templateUrl: './product-updater.component.html',
  styleUrl: './product-adder.component.css',
})
export class ProductUpdaterComponent extends ProductAdderBase {
  @Input() id!: number;
  buttonString: string = 'G�ncelle';
  isImageChanged: boolean = false;

  /**
   * The product version this form was loaded at, submitted back as
   * `expectedVersion`. `null` until the detail read lands and never replaced by
   * a guess.
   */
  productVersion: number | null = null;
  /**
   * True after the backend rejected this update with 409. The draft stays as
   * typed; recovery is a deliberate reload followed by a reviewed save.
   */
  editConflict = false;

  constructor(
    protected override restService: RestService,
    protected override eventManager: EventService
  ) {
    super(restService, eventManager);
    this.isUpdateMode = true;
  }

  override ngOnInit(): void {
    super.ngOnInit();

    this.requests.add(this.restService.getProductImage(this.id).subscribe((blob) => {
      const file = new File([blob], 'image.png', {
        type: 'image/png',
      });
      this.selectedImage = file;
      this.setImagePreview(blob);
    }));

    this.requests.add(this.restService.getProductData(this.id).subscribe((data: IProductData) => {
      this.applyProductData(data);
    }));
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

    // Without a known version there is nothing safe to send: a guess would be
    // indistinguishable from an unguarded absolute-count write.
    if (this.productVersion === null) {
      this.validationError =
        'Product version has not loaded yet. Reload the product and try again.';
      return;
    }

    const packageMetadata = this.readPackageMetadata();

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
      expectedVersion: this.productVersion,
      // Sent on every save, including when both are null: clearing the fields is
      // how package metadata is removed, and omitting them here would leave a
      // cleared field looking unchanged to the server.
      ...packageMetadata,
    };

    this.requests.add(this.restService.updateProductData(productData).subscribe({
      next: (result) => {
        this.editConflict = false;
        if (result.saved) {
          this.outProductAdded();
          this.eventManager.trigger('productAdded');
          // The version this very save produced. Re-reading the product here
          // would pair the just-saved values with a later version: a checkout
          // reserving stock in between would make that version vouch for a
          // count this form never displayed, and the next save would write the
          // stale one back.
          this.productVersion = result.productVersion;
        }
      },
      error: (error) => {
        if (isProductEditConflict(error)) {
          // Draft preserved, and deliberately not resubmitted against a fresh
          // version: that would silently overwrite whatever changed stock.
          this.editConflict = true;
          this.validationError = '';
          return;
        }
        this.editConflict = false;
        this.validationError =
          error?.error ?? 'Product could not be updated. Please check fields.';
      },
    }));
  }

  /**
   * Re-reads the product and replaces the form with the server's current values.
   *
   * The only way out of a conflict, and deliberately manual: the rejected draft
   * is discarded because the administrator asked for it, and the save that
   * follows is a fresh, reviewed one.
   */
  reloadProduct(): void {
    this.validationError = '';
    this.requests.add(
      this.restService.getProductData(this.id).subscribe({
        next: (data: IProductData) => {
          this.applyProductData(data);
          this.editConflict = false;
        },
        error: () => {
          this.validationError = 'Product could not be reloaded.';
        },
      })
    );
  }

  /**
   * Fills the form from a product read.
   *
   * One implementation for the initial load and for the deliberate reload, so
   * the two cannot drift - a field the reload forgot to restore is a field the
   * administrator believes they kept and did not.
   *
   * The package fields are restored exactly as stored, including the null case,
   * which is what a product without a package size looks like. An absent amount
   * becomes an empty input rather than a zero, because zero is a value the
   * backend refuses and an administrator would have to clear before saving.
   */
  private applyProductData(data: IProductData): void {
    this.productName = data.productName ?? '';
    this.subCategoryName = data.subCategoryName ?? '';
    this.price = data.productPrice ?? 0;
    this.count = data.productCount ?? 0;
    this.discount = data.productDiscount ?? 0;
    this.description = data.productDescription ?? '';
    this.selectedFormValue = data.productCategoryId ?? null;
    this.productVersion = data.productVersion ?? null;
    this.packageAmount = data.packageAmount ?? null;
    this.packageUnit = (data.packageUnit ?? '') as ProductPackageUnit | '';

    this.categoryControl.setValue(this.selectedFormValue?.toString() ?? '');
  }
}
