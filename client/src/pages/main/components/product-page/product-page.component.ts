import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { ActivatedRoute, ParamMap, Router, RouterLink } from '@angular/router';
import { combineLatest, Subscription } from 'rxjs';

import { IProductPreview } from '../../../../interfaces/IProductPreview';
import { IProductSearchResponse } from '../../../../interfaces/IProductSearchResponse';
import { ISubCategoryCount } from '../../../../interfaces/ISubCategoryCount';
import { categories, data } from '../../../../memory/global-data';
import { RestService } from '../../../../services/rest/rest.service';
import {
  LISTING_QUERY_PARAM,
  ListingFilterState,
  buildListingQueryParams,
  defaultListingFilters,
  hasSameListingQueryParams,
  listingFiltersFromParams,
  listingRequestSignature,
  parsePageParam,
  pickListingQueryParams,
  resolveListingSubCategoryName,
  toProductSearchQuery,
} from '../../helpers/catalog-listing-state';
import {
  PRODUCT_PAGE_SIZE,
  clampPageToRange,
  pageCountForProductCount,
} from '../../helpers/category-browse-state';
import { ProductBuyComponent } from '../product-buy/product-buy.component';
import {
  ProductListingComponent,
  ProductListingRequest,
} from '../product-listing/product-listing.component';

const ALL_PRODUCTS_LABEL = 'Tüm ürünler';
const DEFAULT_CATEGORY_LABEL = 'Kategori';

/**
 * `/category/:categoryId` and `/category/:categoryId/product/:productId`, plus
 * `/product/:productId` for a product reached from a listing that is not scoped
 * to a category.
 *
 * Both listings load through the same `GET /user/supply/searchProducts` call and
 * render through the same `app-product-listing`. The category page keeps no
 * listing query of its own: the older catalogue endpoints cannot filter, sort or
 * count, so a category page with the filter toolbar the search page has would
 * either have to filter the ten rows it happened to load - paginating before
 * filtering, so pages would hold products that do not match and the total would
 * describe another set - or need a second HTTP flow to avoid it.
 *
 * That does change something a customer can see: the endpoint's default
 * availability is `ALL`, so the category listing now shows sold-out products
 * where the old listing hid them. They are marked `Tükendi`, cannot be added to
 * the cart, and "Stokta" is one tap away.
 */
@Component({
  selector: 'app-product-page',
  standalone: true,
  imports: [CommonModule, RouterLink, ProductListingComponent, ProductBuyComponent],
  templateUrl: './product-page.component.html',
  styleUrl: './product-page.component.css',
})
export class ProductPageComponent implements OnInit, OnDestroy {
  readonly pageSize = PRODUCT_PAGE_SIZE;
  readonly allProductsLabel = ALL_PRODUCTS_LABEL;

  items: IProductPreview[] = [];
  subCategories: ISubCategoryCount[] = [];
  categoryName = DEFAULT_CATEGORY_LABEL;
  currentCategoryId = 0;
  totalProductCount = 0;
  selectedSubCategoryName = '';
  currentPage = 1;
  selectedProductId: number | null = null;
  productName = '';
  isLoading = false;
  hasLoadError = false;
  filters: ListingFilterState = defaultListingFilters();

  private routeSub: Subscription | null = null;
  private searchSub: Subscription | null = null;
  private lastRouteSignature = '';
  private hasCategoryRouteParam = false;
  private requestedSubCategoryParam = '';
  private requestedPageParam = 1;
  private lastRequestSignature = '';
  private isNormalizingUrl = false;
  private standaloneLinkQueryParams: Record<string, string> = {};

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
    this.searchSub?.unsubscribe();
  }

  get isProductDetailView(): boolean {
    return this.selectedProductId !== null;
  }

  get detailProductId(): number {
    return this.selectedProductId ?? 0;
  }

  /**
   * A product detail reached without a category - from a search result, whose
   * product may live anywhere. There is no listing behind it to restore, so the
   * breadcrumb and the return link point back at the search results with the
   * filters that produced them.
   */
  get isStandaloneDetailView(): boolean {
    return this.isProductDetailView && !this.hasCategoryRouteParam;
  }

  get selectionLabel(): string {
    return this.selectedSubCategoryName || ALL_PRODUCTS_LABEL;
  }

  /**
   * The number behind "All products".
   *
   * With nothing selected this is the filtered category total the response
   * reports. With a subcategory selected that total belongs to the selected
   * bucket, so the category's own figure is the sum of the bucket counts the
   * server returned instead - the same numbers shown next to each bucket, so the
   * sidebar stays internally comparable.
   */
  get allProductsCount(): number {
    if (!this.selectedSubCategoryName) {
      return this.totalProductCount;
    }

    return this.subCategories.reduce(
      (sum, bucket) => sum + Math.max(0, bucket.productCount ?? 0),
      0,
    );
  }

  get pageCount(): number {
    return pageCountForProductCount(this.totalProductCount, this.pageSize);
  }

  get categoryLink(): unknown[] {
    return ['/category', this.currentCategoryId];
  }

  get backLinkLabel(): string {
    if (this.isStandaloneDetailView) {
      return 'Arama sonuçlarına dön';
    }

    return this.selectedSubCategoryName
      ? `${this.selectedSubCategoryName} listesine dön`
      : `${this.categoryName} listesine dön`;
  }

  get breadcrumbLabel(): string {
    return this.isStandaloneDetailView ? 'Arama sonuçları' : this.categoryName;
  }

  get breadcrumbLink(): unknown[] {
    return this.isStandaloneDetailView ? ['/search'] : this.categoryLink;
  }

  get detailLinkQueryParams(): Record<string, string> {
    return this.isStandaloneDetailView
      ? this.standaloneLinkQueryParams
      : buildListingQueryParams({
          subCategoryName: this.selectedSubCategoryName,
          page: this.currentPage,
          filters: this.filters,
        });
  }

  get emptyTitle(): string {
    return 'Bu seçimde ürün bulunamadı';
  }

  get emptyText(): string {
    if (this.selectedSubCategoryName) {
      return `${this.selectedSubCategoryName} için şu anda uygun ürün yok.`;
    }

    return 'Bu filtrelerle eşleşen ürün yok. Filtreleri temizleyerek tüm ürünleri görebilirsin.';
  }

  public isSubCategorySelected(subCategoryName: string): boolean {
    return this.selectedSubCategoryName === subCategoryName;
  }

  public selectAllProducts(): void {
    this.navigateTo({ subCategoryName: '', page: 1 });
  }

  public selectSubCategory(subCategoryName: string): void {
    this.navigateTo({ subCategoryName, page: 1 });
  }

  /**
   * The filter toolbar and the paginator both arrive here. Both go through the
   * URL, so a filter change is a history entry the Back button undoes and a
   * shared link reproduces.
   */
  public onListingRequestChange(request: ProductListingRequest): void {
    this.navigateTo({
      subCategoryName: this.selectedSubCategoryName,
      page: request.page,
      filters: request.filters,
    });
  }

  public onPageSelected(page: number): void {
    this.navigateTo({
      subCategoryName: this.selectedSubCategoryName,
      page,
      filters: this.filters,
    });
  }

  public retry(): void {
    this.lastRequestSignature = '';
    this.loadSelection();
  }

  public onProductNameChange(productName: string): void {
    this.productName = productName;
  }

  private navigateTo(state: {
    subCategoryName: string;
    page: number;
    filters?: ListingFilterState;
  }): void {
    // No `queryParamsHandling`: the browse parameters replace whatever the
    // current URL carries, so "All products" really produces a clean URL.
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: buildListingQueryParams({
        subCategoryName: state.subCategoryName,
        page: state.page,
        filters: state.filters ?? this.filters,
      }),
    });
  }

  private applyRouteState(params: ParamMap, queryParams: ParamMap): void {
    // `paramMap` and `queryParamMap` both emit for a single navigation, so the
    // same browsing state must never be applied (or requested) twice.
    const signature = [
      params.get('categoryId') ?? '',
      params.get('productId') ?? '',
      queryParams.get(LISTING_QUERY_PARAM.q) ?? '',
      queryParams.get(LISTING_QUERY_PARAM.subcategory) ?? '',
      queryParams.get(LISTING_QUERY_PARAM.page) ?? '',
      queryParams.get(LISTING_QUERY_PARAM.availability) ?? '',
      queryParams.get(LISTING_QUERY_PARAM.minPrice) ?? '',
      queryParams.get(LISTING_QUERY_PARAM.maxPrice) ?? '',
      queryParams.get(LISTING_QUERY_PARAM.discounted) ?? '',
      queryParams.get(LISTING_QUERY_PARAM.sort) ?? '',
    ].join('|');
    if (signature === this.lastRouteSignature) {
      return;
    }
    this.lastRouteSignature = signature;

    this.requestedSubCategoryParam = (
      queryParams.get(LISTING_QUERY_PARAM.subcategory) ?? ''
    ).trim();
    this.requestedPageParam = parsePageParam(
      queryParams.get(LISTING_QUERY_PARAM.page),
    ) ?? 1;
    this.filters = listingFiltersFromParams(queryParams);

    const rawCategoryId = (params.get('categoryId') ?? '').trim();
    this.hasCategoryRouteParam = rawCategoryId !== '';

    if (!this.hasCategoryRouteParam) {
      this.applyStandaloneDetailState(params, queryParams);
      return;
    }

    const categoryId = Number(rawCategoryId);
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

    if (this.isProductDetailView) {
      // The listing is torn down for the detail view, so returning to this
      // selection must fetch the page again instead of rendering nothing. The
      // requested browse state is kept verbatim for the return link: it cannot be
      // validated here, because only the server knows which subcategories exist.
      this.standaloneLinkQueryParams = {};
      this.selectedSubCategoryName = this.requestedSubCategoryParam;
      this.currentPage = this.requestedPageParam;
      this.items = [];
      this.isLoading = false;
      this.hasLoadError = false;
      this.lastRequestSignature = '';
      this.normalizeUrl();
      return;
    }

    this.selectedSubCategoryName = this.requestedSubCategoryParam;
    this.currentPage = this.requestedPageParam;
    data.currentSelectedSubCategoryName = this.selectedSubCategoryName;
    this.normalizeUrl();
    this.loadSelection();
  }

  /**
   * A product detail with no category behind it. There is no listing to load and
   * no category to resolve, so the only thing that matters is that the return
   * link can put the customer back on the search results they came from with the
   * filters that produced them.
   */
  private applyStandaloneDetailState(
    params: ParamMap,
    queryParams: ParamMap,
  ): void {
    this.standaloneLinkQueryParams = pickListingQueryParams(queryParams);
    this.items = [];
    this.subCategories = [];
    this.totalProductCount = 0;
    this.selectedSubCategoryName = '';
    this.currentPage = 1;
    this.isLoading = false;
    this.hasLoadError = false;
    this.lastRequestSignature = '';

    const productId = this.parseProductId(params.get('productId'));
    if (productId !== this.selectedProductId) {
      this.selectedProductId = productId;
      this.productName = '';
      if (productId !== null) {
        this.scrollToTop();
      }
    }
  }

  private parseProductId(raw: string | null): number | null {
    if (raw === null || raw === undefined || raw.trim() === '') {
      return null;
    }

    const parsed = Number(raw);
    return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
  }

  /**
   * Rewrites the URL to the canonical form so a shared link, a reload and the
   * Back button all describe the same listing. `replaceUrl`, because a hand-typed
   * `?subcategory=Yok` or `?page=99` is not a history entry the customer should
   * have to press Back through twice.
   */
  private normalizeUrl(): void {
    if (this.isNormalizingUrl) {
      return;
    }

    const desired = buildListingQueryParams({
      subCategoryName: this.selectedSubCategoryName,
      page: this.currentPage,
      filters: this.filters,
    });
    if (hasSameListingQueryParams(this.route.snapshot.queryParams, desired)) {
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

  /**
   * The one catalogue query. The page of rows, the filtered total and the
   * subcategory buckets all come out of the same response and are built from the
   * same predicate server-side, so the number in the sidebar, the number in the
   * header and the rows on screen cannot describe three different sets.
   *
   * The subcategory and the page cannot be validated before the request - only
   * the server knows which buckets exist and how many rows they hold - so a
   * requested name that turns out not to exist, or a page past the end, is
   * repaired from the response and refetched once. A stale shared link therefore
   * lands on a real listing instead of a permanently empty one.
   */
  private loadSelection(): void {
    const signature = listingRequestSignature({
      categoryId: this.currentCategoryId,
      subCategoryName: this.selectedSubCategoryName,
      page: this.currentPage,
      size: this.pageSize,
      filters: this.filters,
    });

    if (signature === this.lastRequestSignature) {
      return;
    }

    this.lastRequestSignature = signature;
    this.searchSub?.unsubscribe();
    this.items = [];
    this.isLoading = true;
    this.hasLoadError = false;

    this.searchSub = this.restService
      .searchProducts(
        toProductSearchQuery({
          categoryId: this.currentCategoryId,
          subCategoryName: this.selectedSubCategoryName,
          page: this.currentPage,
          size: this.pageSize,
          filters: this.filters,
        }),
      )
      .subscribe({
        next: (response: IProductSearchResponse) => {
          if (signature !== this.lastRequestSignature) {
            return;
          }

          this.subCategories = response?.subcategories ?? [];
          this.totalProductCount = Math.max(0, response?.totalItems ?? 0);

          if (this.repairSelection()) {
            return;
          }

          this.items = response?.items ?? [];
          this.isLoading = false;
        },
        error: () => {
          if (signature !== this.lastRequestSignature) {
            return;
          }

          this.items = [];
          this.isLoading = false;
          this.hasLoadError = true;
        },
      });
  }

  /**
   * Reconciles the requested selection with what the server reported. Returns
   * `true` when it refetched, so the caller must not render this response.
   */
  private repairSelection(): boolean {
    const resolvedSubCategory = resolveListingSubCategoryName(
      this.subCategories,
      this.selectedSubCategoryName,
    );

    // An unknown name is dropped back to the whole category and refetched, so a
    // stale shared link lands on a real listing instead of a permanently empty one.
    if (resolvedSubCategory !== this.selectedSubCategoryName) {
      this.selectedSubCategoryName = resolvedSubCategory;
      data.currentSelectedSubCategoryName = resolvedSubCategory;
      this.normalizeUrl();
      this.lastRequestSignature = '';
      this.loadSelection();
      return true;
    }

    const resolvedPage = clampPageToRange(this.currentPage, this.pageCount);
    if (resolvedPage !== this.currentPage) {
      this.currentPage = resolvedPage;
      this.normalizeUrl();

      // An empty result has no pages to move between, so there is nothing to
      // refetch - the rows for the clamped page would be empty as well.
      if (this.totalProductCount > 0) {
        this.lastRequestSignature = '';
        this.loadSelection();
        return true;
      }

      this.isLoading = false;
      return false;
    }

    return false;
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