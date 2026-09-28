import { CommonModule } from '@angular/common';
import { Component, Input, OnDestroy, OnInit } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';

import { PRODUCT_IMAGE_PLACEHOLDER } from '../../../../app/config/product-image';
import {
  BrowseQueryParams,
  buildBrowseQueryParams,
  parsePageParam,
} from '../../helpers/category-browse-state';
import { AuthService } from '../../../../services/auth/auth.service';
import { RestService } from '../../../../services/rest/rest.service';
import { ObjectUrlManager } from '../../helpers/object-url-manager';
import { productCartErrorMessage } from '../../helpers/product-cart-error';

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
  @Input() categoryId!: number;

  readonly placeholderImage = PRODUCT_IMAGE_PLACEHOLDER;

  imageUrl: string | null = null;
  browseQueryParams: BrowseQueryParams = {};
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
      this.browseQueryParams = buildBrowseQueryParams(
        params.get('subcategory'),
        parsePageParam(params.get('page')),
      );
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

  get productLink(): unknown[] {
    return ['/category', this.categoryId, 'product', this.productId];
  }

  get addButtonLabel(): string {
    if (this.isOutOfStock) {
      return `${this.productName} stokta yok`;
    }

    return `${this.productName} ürününü sepete ekle`;
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
