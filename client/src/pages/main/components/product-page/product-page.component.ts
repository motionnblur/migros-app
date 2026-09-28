import { HttpErrorResponse } from '@angular/common/http';
import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { ActivatedRoute, ParamMap, Router, RouterLink } from '@angular/router';
import { combineLatest, forkJoin, Subscription } from 'rxjs';

import { IProductPreview } from '../../../../interfaces/IProductPreview';
import { ISubCategory } from '../../../../interfaces/ISubCategory';
import { categories, data } from '../../../../memory/global-data';
import { RestService } from '../../../../services/rest/rest.service';
import {
  PRODUCT_PAGE_SIZE,
  buildBrowseQueryParams,
  clampPageToRange,
  countForSelection,
  hasSameBrowseQueryParams,
  pageCountForProductCount,
  parsePageParam,
  resolveSubCategoryName,
} from '../../helpers/category-browse-state';
import { ProductBuyComponent } from '../product-buy/product-buy.component';
import { ProductPageSwitcherComponent } from '../product-page-switcher/product-page-switcher.component';
import { ProductPreviewComponent } from '../product-preview/product-preview.component';

const ALL_PRODUCTS_LABEL = 'Tüm ürünler';
const DEFAULT_CATEGORY_LABEL = 'Kategori';

@Component({
  selector: 'app-product-page',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    ProductPreviewComponent,
    ProductBuyComponent,
    ProductPageSwitcherComponent,
  ],
  templateUrl: './product-page.component.html',
  styleUrl: './product-page.component.css',
})
export class ProductPageComponent implements OnInit, OnDestroy {
  readonly pageSize = PRODUCT_PAGE_SIZE;
  readonly allProductsLabel = ALL_PRODUCTS_LABEL;

  items: IProductPreview[] = [];
  subCategories: ISubCategory[] = [];
  categoryName = DEFAULT_CATEGORY_LABEL;
  currentCategoryId = 0;
  totalProductCount = 0;
  selectedSubCategoryName = '';
  currentPage = 1;
  selectedProductId: number | null = null;
  productName = '';
  isLoading = false;
  hasLoadError = false;

  private routeSub: Subscription | null = null;
  private lastRouteSignature = '';
  private metadataCategoryId = 0;
  private hasLoadedMetadata = false;
  private isMetadataInFlight = false;
  private hasLoadedSelection = false;
  private requestedSubCategoryParam = '';
  private requestedPageParam: number | null = null;
  private latestRequestId = 0;
  private isNormalizingUrl = false;

  constructor(
    private restService: RestService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.routeSub = combineLatest([
      this.route.paramMap,
      this.route.queryParamMap,
    ]).subscribe(([params, queryParams]) =>
      this.applyRouteState(params, queryParams),
    );
  }

  ngOnDestroy(): void {
    this.routeSub?.unsubscribe();
  }

  get isProductDetailView(): boolean {
    return this.selectedProductId !== null;
  }

  get detailProductId(): number {
    return this.selectedProductId ?? 0;
  }

  get selectionLabel(): string {
    return this.selectedSubCategoryName || ALL_PRODUCTS_LABEL;
  }

  get selectionProductCount(): number {
    return countForSelection(
      this.subCategories,
      this.totalProductCount,
      this.selectedSubCategoryName,
    );
  }

  get pageCount(): number {
    return pageCountForProductCount(this.selectionProductCount, this.pageSize);
  }

  get hasMultiplePages(): boolean {
    return this.pageCount > 1;
  }

  get isEmptySelection(): boolean {
    return !this.isLoading && !this.hasLoadError && this.items.length === 0;
  }

  get categoryLink(): unknown[] {
    return ['/category', this.currentCategoryId];
  }

  get backLinkLabel(): string {
    return this.selectedSubCategoryName
      ? `${this.selectedSubCategoryName} listesine dön`
      : `${this.categoryName} listesine dön`;
  }

  get browseQueryParams() {
    return buildBrowseQueryParams(this.selectedSubCategoryName, this.currentPage);
  }

  public isSubCategorySelected(subCategoryName: string): boolean {
    return this.selectedSubCategoryName === subCategoryName;
  }

  public selectAllProducts(): void {
    this.navigateToSelection('', 1);
  }

  public selectSubCategory(subCategoryName: string): void {
    this.navigateToSelection(subCategoryName, 1);
  }

  public onPageSelected(page: number): void {
    this.navigateToSelection(this.selectedSubCategoryName, page);
  }

  public retry(): void {
    this.latestRequestId++;

    if (this.hasLoadedMetadata) {
      this.hasLoadError = false;
      this.loadSelection();
      return;
    }

    this.loadMetadata();
  }

  public onProductNameChange(productName: string): void {
    this.productName = productName;
  }

  private navigateToSelection(subCategoryName: string, page: number): void {
    // No `queryParamsHandling`: the two browse parameters replace whatever the
    // current URL carries, so "All products" really produces a clean URL.
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: buildBrowseQueryParams(subCategoryName, page),
    });
  }

  private applyRouteState(params: ParamMap, queryParams: ParamMap): void {
    // `paramMap` and `queryParamMap` both emit for a single navigation, so the
    // same browsing state must never be applied (or requested) twice.
    const signature = [
      params.get('categoryId') ?? '',
      params.get('productId') ?? '',
      queryParams.get('subcategory') ?? '',
      queryParams.get('page') ?? '',
    ].join('|');
    if (signature === this.lastRouteSignature) {
      return;
    }
    this.lastRouteSignature = signature;

    this.requestedSubCategoryParam = (queryParams.get('subcategory') ?? '').trim();
    this.requestedPageParam = parsePageParam(queryParams.get('page'));

    const categoryId = Number(params.get('categoryId'));
    if (Number.isFinite(categoryId) && categoryId > 0) {
      this.currentCategoryId = categoryId;
      data.currentSelectedCategoryId = categoryId;
    }
    this.categoryName = this.resolveCategoryName(this.currentCategoryId);

    const productId = this.parseProductId(params.get('productId'));
    if (productId !== this.selectedProductId) {
      this.selectedProductId = productId;
      // The detail view is reused for a different product, so the previous
      // breadcrumb name belongs to a product that is no longer shown.
      this.productName = '';
      if (productId !== null) {
        this.scrollToTop();
      }
    }

    if (this.currentCategoryId !== this.metadataCategoryId) {
      this.loadMetadata();
      return;
    }

    if (this.isMetadataInFlight) {
      // The in-flight metadata load resolves the selection from the requested
      // parameters once the subcategory list is known.
      return;
    }

    if (this.hasLoadedMetadata) {
      this.applySelection();
    }
  }

  private parseProductId(raw: string | null): number | null {
    if (raw === null || raw === undefined || raw.trim() === '') {
      return null;
    }

    const parsed = Number(raw);
    return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
  }

  private applySelection(): void {
    const resolvedSubCategory = resolveSubCategoryName(
      this.subCategories,
      this.requestedSubCategoryParam,
    );
    const resolvedPage = clampPageToRange(
      this.requestedPageParam,
      pageCountForProductCount(
        countForSelection(
          this.subCategories,
          this.totalProductCount,
          resolvedSubCategory,
        ),
        this.pageSize,
      ),
    );

    const selectionChanged = resolvedSubCategory !== this.selectedSubCategoryName;
    const pageChanged = resolvedPage !== this.currentPage;

    this.selectedSubCategoryName = resolvedSubCategory;
    this.currentPage = resolvedPage;
    data.currentSelectedSubCategoryName = resolvedSubCategory;

    this.normalizeUrl(resolvedSubCategory, resolvedPage);

    if (this.isProductDetailView) {
      this.items = [];
      // Invalidate the loaded state and any pending listing response: the
      // listing is torn down for the detail view, so returning to this
      // selection must fetch the page again instead of rendering nothing.
      this.hasLoadedSelection = false;
      this.latestRequestId++;
      return;
    }

    if (selectionChanged || pageChanged || !this.hasLoadedSelection) {
      this.loadSelection();
    }
  }

  private normalizeUrl(subCategoryName: string, page: number): void {
    if (this.isNormalizingUrl) {
      return;
    }

    const desired = buildBrowseQueryParams(subCategoryName, page);
    if (hasSameBrowseQueryParams(this.route.snapshot.queryParams, desired)) {
      return;
    }

    this.isNormalizingUrl = true;
    this.router
      .navigate([], {
        relativeTo: this.route,
        queryParams: desired,
        replaceUrl: true,
      })
      .finally(() => {
        this.isNormalizingUrl = false;
      });
  }

  private loadMetadata(): void {
    const requestId = ++this.latestRequestId;
    const categoryId = this.currentCategoryId;

    this.metadataCategoryId = categoryId;
    this.hasLoadedMetadata = false;
    this.isMetadataInFlight = true;
    this.hasLoadedSelection = false;
    this.subCategories = [];
    this.items = [];
    this.totalProductCount = 0;
    this.selectedSubCategoryName = '';
    this.currentPage = 1;
    this.isLoading = true;
    this.hasLoadError = false;

    forkJoin({
      subCategories: this.restService.getSubCategories(categoryId),
      totalCount: this.restService.getProductCountsFromCategory(categoryId),
    }).subscribe({
      next: ({ subCategories, totalCount }) => {
        if (requestId !== this.latestRequestId) {
          return;
        }

        this.subCategories = subCategories ?? [];
        this.totalProductCount = totalCount ?? 0;
        this.hasLoadedMetadata = true;
        this.isMetadataInFlight = false;
        this.isLoading = false;
        this.applySelection();
      },
      error: () => {
        if (requestId !== this.latestRequestId) {
          return;
        }

        this.isMetadataInFlight = false;
        this.isLoading = false;
        this.hasLoadError = true;
      },
    });
  }

  private loadSelection(): void {
    const requestId = ++this.latestRequestId;
    const categoryId = this.currentCategoryId;
    const subCategoryName = this.selectedSubCategoryName;
    const pageIndex = this.currentPage - 1;

    this.hasLoadError = false;

    if (countForSelection(this.subCategories, this.totalProductCount, subCategoryName) === 0) {
      this.items = [];
      this.hasLoadedSelection = true;
      this.isLoading = false;
      return;
    }

    this.isLoading = true;

    const request$ = subCategoryName
      ? this.restService.getProducstFromSubCategory(
          subCategoryName,
          pageIndex,
          this.pageSize,
        )
      : this.restService.getProductPageData(categoryId, pageIndex, this.pageSize);

    request$.subscribe({
      next: (items) => {
        if (requestId !== this.latestRequestId) {
          return;
        }

        this.items = items ?? [];
        this.hasLoadedSelection = true;
        this.isLoading = false;
      },
      error: (error: unknown) => {
        if (requestId !== this.latestRequestId) {
          return;
        }

        this.items = [];
        this.hasLoadedSelection = true;
        this.isLoading = false;
        this.hasLoadError = !this.isEmptyCategoryListing(error);
      },
    });
  }

  /**
   * `getProductsFromCategory` answers with a plain 404 when the category holds
   * no in-stock product. That is an empty listing, not a failure.
   */
  private isEmptyCategoryListing(error: unknown): boolean {
    if (this.selectedSubCategoryName) {
      return false;
    }

    const httpError = error as HttpErrorResponse | null;
    if (httpError?.status !== 404) {
      return false;
    }

    const body = typeof httpError.error === 'string' ? httpError.error.trim() : '';
    return body === '' || body === String(this.currentCategoryId);
  }

  private resolveCategoryName(categoryId: number): string {
    return categories.find((category) => category.value === categoryId)?.name ??
      DEFAULT_CATEGORY_LABEL;
  }

  private scrollToTop(): void {
    if (typeof window === 'undefined' || typeof window.scrollTo !== 'function') {
      return;
    }

    const reducedMotion =
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    window.scrollTo({ top: 0, behavior: reducedMotion ? 'auto' : 'smooth' });
  }
}
