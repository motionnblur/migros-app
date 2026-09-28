import { CommonModule } from '@angular/common';
import {
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnChanges,
  OnDestroy,
  Output,
  QueryList,
  SimpleChanges,
  ViewChildren,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription } from 'rxjs';

import { PRODUCT_IMAGE_PLACEHOLDER } from '../../../../app/config/product-image';
import { AuthService } from '../../../../services/auth/auth.service';
import { RestService } from '../../../../services/rest/rest.service';
import { IDescription } from '../../../../interfaces/IDescription';
import { IProductData } from '../../../../interfaces/IProductData';
import { IProductDescription } from '../../../../interfaces/IProductDescription';
import { ObjectUrlManager } from '../../helpers/object-url-manager';
import { productCartErrorMessage } from '../../helpers/product-cart-error';

export type BuyFeedbackKind = 'success' | 'error';

const FEEDBACK_DURATION_MS = 4000;

@Component({
  selector: 'app-product-buy',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './product-buy.component.html',
  styleUrl: './product-buy.component.css',
})
export class ProductBuyComponent implements OnChanges, OnDestroy {
  @Input() productId!: number;
  @Output() productNameChange = new EventEmitter<string>();

  @ViewChildren('tabButton') tabButtons!: QueryList<ElementRef<HTMLButtonElement>>;

  readonly placeholderImage = PRODUCT_IMAGE_PLACEHOLDER;

  productData: IProductData | null = null;
  productDescriptions: IProductDescription | null = null;
  productImageUrl: string | null = null;
  currentProductDescriptionBody = '';
  selectedTabIndex = 0;
  isLoading = true;
  hasLoadError = false;
  isAddingToCart = false;
  feedbackKind: BuyFeedbackKind | null = null;
  feedbackMessage = '';

  private readonly imageUrls = new ObjectUrlManager();
  private dataSub: Subscription | null = null;
  private descriptionSub: Subscription | null = null;
  private imageSub: Subscription | null = null;
  private cartSub: Subscription | null = null;
  private feedbackTimeout: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private restService: RestService,
    private authService: AuthService,
    private router: Router,
    private route: ActivatedRoute,
  ) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['productId']) {
      this.loadProduct();
    }
  }

  ngOnDestroy(): void {
    this.dataSub?.unsubscribe();
    this.descriptionSub?.unsubscribe();
    this.imageSub?.unsubscribe();
    this.cartSub?.unsubscribe();
    this.clearFeedbackTimeout();
    this.releaseImageUrl();
  }

  get descriptionList(): IDescription[] {
    return this.productDescriptions?.descriptionList ?? [];
  }

  get hasDescriptions(): boolean {
    return this.descriptionList.length > 0;
  }

  public get discountedPrice(): number {
    if (!this.productData) return 0;

    const price = this.productData.productPrice ?? 0;
    const discount = this.productData.productDiscount ?? 0;

    if (discount <= 0) {
      return price;
    }

    return +(price - (price * discount) / 100).toFixed(2);
  }

  public formatPrice(value: number | null | undefined): string {
    return (value ?? 0).toFixed(2);
  }

  public get isOutOfStock(): boolean {
    return (this.productData?.productCount ?? 0) <= 0;
  }

  public get addButtonLabel(): string {
    const name = this.productData?.productName ?? 'Ürün';
    return this.isOutOfStock
      ? `${name} stokta yok`
      : `${name} ürününü sepete ekle`;
  }

  public retry(): void {
    this.loadProduct();
  }

  public addProductToUserCart(): void {
    if (this.isOutOfStock) {
      this.showFeedback('error', 'Bu ürün stokta kalmadı.');
      return;
    }

    if (!this.authService.isLoggedIn()) {
      this.router.navigate([{ outlets: { modal: ['login'] } }], {
        relativeTo: this.route,
      });
      return;
    }

    this.isAddingToCart = true;
    this.cartSub = this.restService.addProductToUserCart(this.productId).subscribe({
      next: () => {
        this.isAddingToCart = false;
      },
      error: (error: unknown) => {
        this.isAddingToCart = false;
        this.showFeedback('error', productCartErrorMessage(error));
      },
      complete: () => this.showFeedback('success', 'Ürün sepete eklendi.'),
    });
  }

  public onTabClick(index: number): void {
    this.selectTab(index);
  }

  /** Arrow/Home/End navigation between description tabs. */
  public onTabKeydown(event: KeyboardEvent, index: number): void {
    const lastIndex = this.descriptionList.length - 1;
    if (lastIndex < 0) {
      return;
    }

    let nextIndex: number | null = null;
    switch (event.key) {
      case 'ArrowRight':
        nextIndex = index >= lastIndex ? 0 : index + 1;
        break;
      case 'ArrowLeft':
        nextIndex = index <= 0 ? lastIndex : index - 1;
        break;
      case 'Home':
        nextIndex = 0;
        break;
      case 'End':
        nextIndex = lastIndex;
        break;
      default:
        return;
    }

    event.preventDefault();
    this.selectTab(nextIndex);
    this.focusTab(nextIndex);
  }

  public tabId(index: number): string {
    return `product-description-tab-${this.productId}-${index}`;
  }

  public tabPanelId(): string {
    return `product-description-panel-${this.productId}`;
  }

  private selectTab(index: number): void {
    if (index < 0 || index >= this.descriptionList.length) {
      return;
    }

    this.selectedTabIndex = index;
    // Kept as a plain string: the `[innerHTML]` binding sanitizes the stored
    // admin-entered markup, so no event handler or unsafe URL survives.
    this.currentProductDescriptionBody = this.descriptionList[index].descriptionTabContent ?? '';
  }

  private focusTab(index: number): void {
    const target = this.tabButtons?.get(index)?.nativeElement;
    target?.focus();
  }

  private loadProduct(): void {
    this.dataSub?.unsubscribe();
    this.descriptionSub?.unsubscribe();
    this.imageSub?.unsubscribe();
    // A pending add-to-cart belongs to the previous product: its feedback must
    // not surface on the new one.
    this.cartSub?.unsubscribe();
    this.cartSub = null;

    this.isLoading = true;
    this.hasLoadError = false;
    this.isAddingToCart = false;
    this.productData = null;
    this.productDescriptions = null;
    this.selectedTabIndex = 0;
    this.currentProductDescriptionBody = '';
    // The old product's image must not stay visible next to the new data.
    this.releaseImageUrl();
    this.productImageUrl = null;
    this.clearFeedbackTimeout();
    this.feedbackKind = null;
    this.feedbackMessage = '';

    this.dataSub = this.restService.getProductData(this.productId).subscribe({
      next: (productData) => {
        this.productData = productData;
        this.isLoading = false;
        this.productNameChange.emit(productData?.productName ?? '');
        this.loadDescriptions();
        this.loadImage();
      },
      error: () => {
        this.productData = null;
        this.isLoading = false;
        this.hasLoadError = true;
      },
    });
  }

  /** Descriptions are supplementary: a failure must not hide the product. */
  private loadDescriptions(): void {
    this.descriptionSub = this.restService
      .getProductDescription(this.productId)
      .subscribe({
        next: (description) => {
          this.productDescriptions = description;
          this.selectTab(0);
        },
        error: () => {
          this.productDescriptions = null;
          this.currentProductDescriptionBody = '';
        },
      });
  }

  private loadImage(): void {
    this.imageSub = this.restService.getProductImage(this.productId).subscribe({
      next: (blob) => this.setImage(blob),
      error: () => this.clearImage(),
    });
  }

  private setImage(blob: Blob): void {
    this.productImageUrl = this.imageUrls.create(blob);
  }

  private clearImage(): void {
    this.releaseImageUrl();
    this.productImageUrl = null;
  }

  private releaseImageUrl(): void {
    this.imageUrls.release();
  }

  private showFeedback(kind: BuyFeedbackKind, message: string): void {
    this.clearFeedbackTimeout();
    this.feedbackKind = kind;
    this.feedbackMessage = message;
    this.feedbackTimeout = setTimeout(() => {
      this.feedbackKind = null;
      this.feedbackMessage = '';
      this.feedbackTimeout = null;
    }, FEEDBACK_DURATION_MS);
  }

  private clearFeedbackTimeout(): void {
    if (this.feedbackTimeout !== null) {
      clearTimeout(this.feedbackTimeout);
      this.feedbackTimeout = null;
    }
  }
}
