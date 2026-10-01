import { CommonModule } from '@angular/common';
import { Component, Input, OnDestroy, OnInit } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';

import { PRODUCT_IMAGE_PLACEHOLDER } from '../../../../app/config/product-image';
import { pickListingQueryParams } from '../../helpers/catalog-listing-state';
import { AuthService } from '../../../../services/auth/auth.service';
import { RestService } from '../../../../services/rest/rest.service';
import { ObjectUrlManager } from '../../helpers/object-url-manager';
import { formatAmount } from '../../helpers/money-format';
import { productCartErrorMessage } from '../../helpers/product-cart-error';
import {
  packageSizeLabel,
  unitPriceBasisLabel,
  unitPriceLabel,
} from '../../helpers/product-package-price';

export type CartFeedbackKind = 'success' | 'error';

const FEEDBACK_DURATION_MS = 4000;

@Component({
  selector: 'app-product-preview',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './product-preview.component.html',
  styleUrl: './product-preview.component.css',
})
export class ProductPreviewComponent implements OnInit, OnDestroy {
  @Input() productId!: number;
  @Input() productName!: string;
  @Input() productPrice!: number;
  @Input() productCount!: number;
  /**
   * The category the detail route is built from, or `null` for a listing that is
   * not scoped to one. A search result may belong to any category, so there is no
   * category detail URL to build for it - the card links to the category-less
   * product route instead rather than to a guessed category id.
   */
  @Input() categoryId: number | null = null;
  /**
   * Optional package size, passed straight through from the listing row.
   *
   * `null` for every product that predates package metadata, and it is the normal
   * case rather than a missing input: the card then renders no package line at
   * all. The amount is not rounded or reformatted here.
   */
  @Input() packageAmount: number | null = null;
  @Input() packageUnit: string | null = null;
  /**
   * The server-computed price per basis unit. Never divided here - the backend
   * owns that arithmetic, and a second division in the browser is a number that
   * can disagree with the package price beside it.
   */
  @Input() unitPrice: number | null = null;
  @Input() unitPriceBasis: string | null = null;

  readonly placeholderImage = PRODUCT_IMAGE_PLACEHOLDER;

  imageUrl: string | null = null;
  browseQueryParams: Record<string, string> = {};
  isAddingToCart = false;
  feedbackKind: CartFeedbackKind | null = null;
  feedbackMessage = '';

  private readonly imageUrls = new ObjectUrlManager();
  private routeSub: Subscription | null = null;
  private imageSub: Subscription | null = null;
  private cartSub: Subscription | null = null;
  private feedbackTimeout: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private restService: RestService,
    private authService: AuthService,
    private router: Router,
    private route: ActivatedRoute,
  ) {}

  ngOnInit(): void {
    this.routeSub = this.route.queryParamMap.subscribe((params) => {
      this.browseQueryParams = pickListingQueryParams(params);
    });

    if (this.productId) {
      this.imageSub = this.restService.getProductImage(this.productId).subscribe({
        next: (blob: Blob) => this.setImage(blob),
        error: () => this.clearImage(),
      });
    }
  }

  ngOnDestroy(): void {
    this.routeSub?.unsubscribe();
    this.imageSub?.unsubscribe();
    this.cartSub?.unsubscribe();
    this.clearFeedbackTimeout();
    this.releaseImageUrl();
  }

  get isOutOfStock(): boolean {
    return (this.productCount ?? 0) <= 0;
  }

  /**
   * The price as a customer reads a price in this storefront: `49,90`.
   *
   * <p>The number is the one the server sent and is untouched; only the notation is
   * the storefront's. It used to go through Angular's `number` pipe, which follows
   * whatever locale the browser reports - so the same card showed `49.90` beside a
   * cart total of `49,90 TL`, and which of the two a customer saw depended on their
   * device rather than on the product.
   */
  get formattedPrice(): string {
    return formatAmount(this.productPrice);
  }

  get hasCategoryLink(): boolean {
    return (
      this.categoryId !== null &&
      this.categoryId !== undefined &&
      Number.isFinite(this.categoryId) &&
      this.categoryId > 0
    );
  }

  get productLink(): unknown[] {
    return this.hasCategoryLink
      ? ['/category', this.categoryId, 'product', this.productId]
      : ['/product', this.productId];
  }

  get addButtonLabel(): string {
    if (this.isOutOfStock) {
      return `${this.productName} stokta yok`;
    }

    return `${this.productName} ürününü sepete ekle`;
  }

  /**
   * "1,5 L", or `null` for a product with no package size.
   *
   * Up to three decimals, because that is the precision the amount can carry, and
   * `1` for a stored `1.5` would misreport the product the customer is about to buy.
   */
  get packageSizeText(): string | null {
    const amount = this.packageAmount;
    if (amount === null || amount === undefined) {
      return null;
    }
    return packageSizeLabel(amount, this.packageUnit);
  }

  /**
   * "24,99 TL/kg", or `null` when there is no unit price to show.
   *
   * Formatted here rather than with a template pipe because the same string is
   * asserted in tests and reused by the accessible label; going through one
   * function is what keeps the visible text and the spoken text identical.
   */
  get unitPriceText(): string | null {
    const price = this.unitPrice;
    return price === null || price === undefined
      ? null
      : unitPriceLabel(price, this.unitPriceBasis);
  }

  /**
   * The "per" label on its own, for the visually separated unit suffix.
   *
   * `null` whenever {@link unitPriceText} is `null`, so the two can never appear
   * on their own - a unit with no number, or a number with no unit, are both
   * worse than neither.
   */
  get unitPriceBasisText(): string | null {
    return this.unitPriceText === null
      ? null
      : unitPriceBasisLabel(this.unitPriceBasis);
  }

  /**
   * The package size spoken in full for assistive technology.
   *
   * The visual line is split into a number and a unit so the unit can sit in
   * muted type; that split is meaningless read aloud, so the accessible name
   * carries the two together.
   */
  get packageSizeAriaLabel(): string | null {
    return this.packageSizeText === null ? null : `Paket ${this.packageSizeText}`;
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
      complete: () => {
        this.showFeedback('success', `${this.productName} sepete eklendi.`);
      },
    });
  }

  private showFeedback(kind: CartFeedbackKind, message: string): void {
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

  private setImage(blob: Blob): void {
    this.imageUrl = this.imageUrls.create(blob);
  }

  private clearImage(): void {
    this.imageUrls.release();
    this.imageUrl = null;
  }

  private releaseImageUrl(): void {
    this.imageUrls.release();
  }
}