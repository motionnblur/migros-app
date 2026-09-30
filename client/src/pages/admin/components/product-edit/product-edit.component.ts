import { Component, EventEmitter, Input, Output } from '@angular/core';
import { ProductBuyBase } from '../../../../base-components/product-buy.base';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RestService } from '../../../../services/rest/rest.service';
import { EventService } from '../../../../services/event/event.service';
import { IDescription } from '../../../../interfaces/IDescription';
import { IProductData } from '../../../../interfaces/IProductData';
import { IProductDescription } from '../../../../interfaces/IProductDescription';
import { IProductUpdater } from '../../../../interfaces/IProductUpdater';
import { isProductEditConflict } from '../../../../services/rest/product-edit-conflict';
import { categories } from '../../../../memory/global-data';
import { ToastService } from '../../services/toast.service';

@Component({
  selector: 'app-product-edit',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './product-edit.component.html',
  styleUrl: './product-edit.component.css',
})
export class ProductEditComponent extends ProductBuyBase {
  @Input() selectedImage: File | null = null;
  @Output() hasEscapePressed = new EventEmitter<boolean>();

  readonly categories = categories;

  currentSelectedTabIndex = 0;
  localProductDescriptions: IProductDescription | null = null;
  productDescriptionTabsToDelete: number[] = [];

  productName = '';
  subCategoryName = '';
  price = 0;
  count = 0;
  discount = 0;
  description = '';
  categoryValue: number | null = null;

  isSavingProduct = false;
  isSavingDescriptions = false;
  validationError = '';
  saveError = '';

  /**
   * The product version this form was loaded at, submitted back as
   * `expectedVersion`. `null` until the detail read lands, and a null version is
   * never replaced with a guess.
   */
  productVersion: number | null = null;
  /**
   * True after the backend rejected this edit with 409. The draft fields are
   * left exactly as typed; recovery is a deliberate reload, never an automatic
   * retry against a freshly fetched version.
   */
  editConflict = false;

  private boundKeyDownEvent!: (event: KeyboardEvent) => void;

  constructor(
    protected override restService: RestService,
    protected override eventManager: EventService,
    private toastService: ToastService
  ) {
    super(restService, eventManager);
    this.boundKeyDownEvent = this.keyDownEvent.bind(this);
  }

  override ngOnInit() {
    super.ngOnInit();
    document.addEventListener('keydown', this.boundKeyDownEvent);
  }

  override ngOnDestroy() {
    super.ngOnDestroy();
    document.removeEventListener('keydown', this.boundKeyDownEvent);
  }

  protected override onProductDescritptionUpdate(
    data: IProductDescription
  ): void {
    if (this.localProductDescriptions === null && data) {
      this.localProductDescriptions = JSON.parse(JSON.stringify(data));
    }
  }

  protected override onProductDataUpdate(data: IProductData): void {
    this.productName = data.productName ?? '';
    this.subCategoryName = data.subCategoryName ?? '';
    this.price = data.productPrice ?? 0;
    this.count = data.productCount ?? 0;
    this.discount = data.productDiscount ?? 0;
    this.description = data.productDescription ?? '';
    this.categoryValue = data.productCategoryId ?? null;
    this.productVersion = data.productVersion ?? null;
  }

  private keyDownEvent(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      this.hasEscapePressed.emit(true);
    }
  }

  get descriptionList(): IDescription[] {
    return this.productDescriptions?.descriptionList ?? [];
  }

  get currentTabContent(): string {
    return this.descriptionList[this.currentSelectedTabIndex]?.descriptionTabContent ?? '';
  }

  selectTab(index: number): void {
    this.currentSelectedTabIndex = index;
  }

  onDescriptionBodyInput(value: string): void {
    const tab = this.descriptionList[this.currentSelectedTabIndex];
    if (!tab) {
      return;
    }
    tab.descriptionTabContent = value;
    this.currentProductDescriptionBody = value;
  }

  addDescriptionTab(): void {
    if (!this.productDescriptions) {
      this.productDescriptions = { productId: this.productId, descriptionList: [] };
    }
    if (!this.productDescriptions.descriptionList) {
      this.productDescriptions.descriptionList = [];
    }

    this.productDescriptions.descriptionList.push({
      descriptionId: 0,
      descriptionTabName: 'Yeni Sekme',
      descriptionTabContent: '',
    });
    this.currentSelectedTabIndex =
      this.productDescriptions.descriptionList.length - 1;
    this.currentProductDescriptionBody = '';
  }

  removeDescriptionTab(event: Event, index: number): void {
    event.stopPropagation();

    const tab = this.descriptionList[index];
    if (!tab) {
      return;
    }

    if (tab.descriptionId) {
      this.productDescriptionTabsToDelete.push(tab.descriptionId);
    }

    this.productDescriptions.descriptionList.splice(index, 1);

    const nextIndex = Math.max(
      0,
      Math.min(this.currentSelectedTabIndex, this.descriptionList.length - 1)
    );
    this.currentSelectedTabIndex = this.descriptionList.length ? nextIndex : 0;
    this.currentProductDescriptionBody = this.currentTabContent;
  }

  saveProduct(): void {
    if (this.isSavingProduct) {
      return;
    }

    if (!this.productName.trim() || !this.subCategoryName.trim()) {
      this.validationError = 'Ürün adı ve alt kategori zorunludur.';
      return;
    }

    if (this.categoryValue === null) {
      this.validationError = 'Kategori seçimi zorunludur.';
      return;
    }

    if (this.price < 0 || this.count < 0 || this.discount < 0 || this.discount > 100) {
      this.validationError = 'Fiyat/miktar/indirim değerleri geçersiz.';
      return;
    }

    // Without a known version there is nothing safe to send: a guess would be
    // indistinguishable from an unguarded write.
    if (this.productVersion === null) {
      this.validationError = 'Ürün sürümü yüklenmedi. Ürünü yeniden yükleyip tekrar deneyin.';
      return;
    }

    this.validationError = '';
    this.saveError = '';
    this.isSavingProduct = true;

    const productData: IProductUpdater = {
      adminId: 1,
      productId: this.productId,
      productName: this.productName.trim(),
      subCategoryName: this.subCategoryName.trim(),
      productPrice: this.price,
      productCount: this.count,
      productDiscount: this.discount,
      productDescription: this.description.trim(),
      selectedImage: this.selectedImage,
      categoryValue: this.categoryValue,
      expectedVersion: this.productVersion,
    };

    this.requests.add(this.restService.updateProductData(productData).subscribe({
      next: (result) => {
        this.isSavingProduct = false;
        this.editConflict = false;
        if (result.saved) {
          this.eventManager.trigger('productAdded');
          this.toastService.success('Ürün kaydedildi.');
          // The version the save itself produced, and nothing else. Re-reading
          // the product here would pair these just-saved field values with a
          // version fetched afterwards: if a checkout reserved stock in between,
          // that newer version vouches for a stock count this form never showed,
          // and the next save would write the stale count straight back.
          this.productVersion = result.productVersion;
        }
      },
      error: (error) => {
        this.isSavingProduct = false;
        if (isProductEditConflict(error)) {
          // The draft is left untouched on purpose. Resubmitting it against a
          // freshly fetched version would silently overwrite whatever moved
          // stock in the meantime, which is the failure this guard exists for.
          this.editConflict = true;
          this.saveError = '';
          this.toastService.error('Ürün kaydedilemedi.');
          return;
        }
        this.editConflict = false;
        this.saveError =
          error?.error ?? 'Ürün kaydedilemedi. Lütfen alanları kontrol edin.';
        this.toastService.error('Ürün kaydedilemedi.');
      },
    }));
  }

  /**
   * Re-reads the product and replaces the form with the server's current values.
   *
   * This is the only way out of a conflict, and it is deliberately manual: the
   * rejected draft is discarded only because the administrator asked for it, and
   * the save that follows is a fresh, reviewed one.
   */
  reloadProduct(): void {
    this.saveError = '';
    this.validationError = '';
    this.requests.add(
      this.restService.getProductData(this.productId).subscribe({
        next: (data: IProductData) => {
          this.onProductDataUpdate(data);
          this.editConflict = false;
        },
        error: () => {
          this.saveError = 'Ürün yeniden yüklenemedi.';
        },
      })
    );
  }

  saveDescriptions(): void {
    if (this.isSavingDescriptions || !this.productDescriptions) {
      return;
    }

    this.saveError = '';
    this.isSavingDescriptions = true;

    if (this.productDescriptionTabsToDelete.length > 0) {
      this.productDescriptionTabsToDelete.forEach((descriptionId) => {
        this.requests.add(this.restService.deleteProductDescription(descriptionId).subscribe());
      });
      this.productDescriptionTabsToDelete = [];
    }

    this.productDescriptions.productId = this.productId;

    if (
      JSON.stringify(this.localProductDescriptions) ===
      JSON.stringify(this.productDescriptions)
    ) {
      this.isSavingDescriptions = false;
      return;
    }

    this.requests.add(this.restService.addProductDescription(this.productDescriptions).subscribe({
      next: (status: boolean) => {
        this.isSavingDescriptions = false;
        if (status) {
          this.localProductDescriptions = JSON.parse(
            JSON.stringify(this.productDescriptions)
          );
          this.toastService.success('Açıklama sekmeleri kaydedildi.');
        }
      },
      error: (error) => {
        this.isSavingDescriptions = false;
        this.saveError = error?.error ?? 'Açıklamalar kaydedilemedi.';
        this.toastService.error('Açıklamalar kaydedilemedi.');
      },
    }));
  }

  onImageSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.selectedImage = input.files[0];
      this.releaseImageUrl();
      this.productImageUrl = URL.createObjectURL(this.selectedImage);
    }
  }

  private releaseImageUrl(): void {
    if (this.productImageUrl) {
      URL.revokeObjectURL(this.productImageUrl);
      this.productImageUrl = null;
    }
  }
}
