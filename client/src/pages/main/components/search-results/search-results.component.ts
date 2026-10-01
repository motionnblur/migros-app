import { Component, OnDestroy, OnInit } from '@angular/core';
import { ActivatedRoute, ParamMap, Router } from '@angular/router';
import { Subscription } from 'rxjs';

import { IProductPreview } from '../../../../interfaces/IProductPreview';
import { IProductSearchResponse } from '../../../../interfaces/IProductSearchResponse';
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
  toProductSearchQuery,
} from '../../helpers/catalog-listing-state';
import {
  PRODUCT_PAGE_SIZE,
  clampPageToRange,
  pageCountForProductCount,
} from '../../helpers/category-browse-state';
import {
  ProductListingComponent,
  ProductListingRequest,
} from '../product-listing/product-listing.component';

/**
 * `GET /search` - the product results for a term, filtered and sorted by the
 * same endpoint and rendered by the same presentation the category listing uses.
 *
 * There is deliberately no category and no subcategory here. A search result may
 * belong to any category, so presenting it as belonging to one would be a
 * guess; and the endpoint refuses a subcategory without a category precisely
 * because that name means nothing on its own. The only state this route owns is
 * the term and the filter group, and all of it is in the URL so a results page is
 * shareable and the Back button walks the customer's own filter history.
 */
@Component({
  selector: 'app-search-results',
  standalone: true,
  imports: [ProductListingComponent],
  templateUrl: './search-results.component.html',
  styleUrl: './search-results.component.css',
})
export class SearchResultsComponent implements OnInit, OnDestroy {
  readonly pageSize = PRODUCT_PAGE_SIZE;

  items: IProductPreview[] = [];
  totalItems = 0;
  currentPage = 1;
  queryTerm = '';
  filters: ListingFilterState = defaultListingFilters();
  isLoading = false;
  hasLoadError = false;

  private routeSub: Subscription | null = null;
  private searchSub: Subscription | null = null;
  private lastRequestSignature = '';
  private lastAppliedSignature = '';
  private isNormalizingUrl = false;

  constructor(
    private restService: RestService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.routeSub = this.route.queryParamMap.subscribe((params) =>
      this.applyRouteState(params),
    );
  }

  ngOnDestroy(): void {
    this.routeSub?.unsubscribe();
    this.searchSub?.unsubscribe();
  }

  get heading(): string {
    return this.queryTerm ? `“${this.queryTerm}” için sonuçlar` : 'Tüm ürünler';
  }

  get selectionLabel(): string {
    return this.queryTerm ? `Arama: ${this.queryTerm}` : '';
  }

  get pageCount(): number {
    return pageCountForProductCount(this.totalItems, this.pageSize);
  }

  get hasMultiplePages(): boolean {
    return this.pageCount > 1;
  }

  get emptyTitle(): string {
    if (this.queryTerm && this.isFiltered) {
      return 'Bu filtrelerle eşleşen ürün bulunamadı';
    }

    return this.queryTerm ? 'Bu aramayla eşleşen ürün bulunamadı' : 'Listelenecek ürün yok';
  }

  get emptyText(): string {
    if (this.queryTerm && this.isFiltered) {
      return 'Filtreleri gevşetmeyi ya da filtreleri temizlemeyi deneyebilirsin.';
    }

    if (this.queryTerm) {
      return 'Farklı bir ürün adı deneyebilir ya da filtreleri temizleyebilirsin.';
    }

    return 'Şu anda katalogda görüntülenecek ürün yok.';
  }

  get isFiltered(): boolean {
    return (
      this.filters.availability !== 'ALL' ||
      !!this.filters.minPrice ||
      !!this.filters.maxPrice ||
      this.filters.discountedOnly ||
      this.filters.sort !== 'DEFAULT'
    );
  }

  /**
   * The filter controls and the paginator both land here, and both reset to the
   * first page: a narrowed result usually has fewer pages, so the page number
   * the customer was on is very often gone.
   */
  public onRequestChange(request: ProductListingRequest): void {
    this.navigateTo({ filters: request.filters, page: request.page });
  }

  public retry(): void {
    this.lastRequestSignature = '';
    this.load();
  }

  private applyRouteState(params: ParamMap): void {
    // The term is the subject of the page, so it is read from the URL on every
    // emission; the filters come from the same place, normalized.
    const term = (params.get(LISTING_QUERY_PARAM.q) ?? '').trim();
    const requestedFilters = listingFiltersFromParams(params);
    // Not clamped yet: the page count is the server's answer, so a link to a page
    // past the end has to be asked for before it can be repaired.
    const requestedPage = parsePageParam(params.get(LISTING_QUERY_PARAM.page)) ?? 1;

    // Compared as one request rather than field by field: the term, a control and
    // the page all feed the same query, so a change to any of them is the same kind
    // of change. Watching only the filters and the page would make a new term a
    // silent no-op, because both of those are at their defaults either way.
    const signature = listingRequestSignature({
      q: term,
      page: requestedPage,
      filters: requestedFilters,
    });

    this.queryTerm = term;
    this.filters = requestedFilters;
    this.currentPage = requestedPage;

    this.normalizeUrl();

    if (signature !== this.lastAppliedSignature) {
      this.lastAppliedSignature = signature;
      this.load();
    }
  }

  private navigateTo(state: { filters: ListingFilterState; page: number }): void {
    // No `queryParamsHandling`: the term is part of this route's identity, so a
    // filter change replaces the parameters instead of merging into whatever the
    // URL carried before.
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: buildListingQueryParams({
        q: this.queryTerm,
        page: state.page,
        filters: state.filters,
      }),
    });
  }

  /**
   * Rewrites the URL to the canonical form so a shared link, a reload and the
   * Back button all describe the same listing. `replaceUrl`, because a hand-typed
   * `?availability=in_stock` is not a history entry the customer should have to
   * press Back through twice.
   */
  private normalizeUrl(): void {
    if (this.isNormalizingUrl) {
      return;
    }

    const desired = buildListingQueryParams({
      q: this.queryTerm,
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
   * The one catalogue query.
   *
   * The page cannot be clamped before the request, because the number of pages is
   * the server's answer: a shared link to `?page=3` must be asked for page 3 to
   * learn whether page 3 exists. So the requested page goes out as-is and is
   * repaired from the response.
   */
  private load(): void {
    const signature = listingRequestSignature({
      q: this.queryTerm,
      page: this.currentPage,
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
          q: this.queryTerm,
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

          this.totalItems = Math.max(0, response?.totalItems ?? 0);

          if (this.repairPage()) {
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
          this.totalItems = 0;
          this.isLoading = false;
          this.hasLoadError = true;
        },
      });
  }

  /**
   * Reconciles the requested page with what the server reported. Returns `true`
   * when it refetched, so the caller must not render this response.
   */
  private repairPage(): boolean {
    const resolvedPage = clampPageToRange(this.currentPage, this.pageCount);
    if (resolvedPage === this.currentPage) {
      return false;
    }

    this.currentPage = resolvedPage;
    this.normalizeUrl();

    // An empty result has no pages to move between, so there is nothing to
    // refetch - the clamped page would be empty as well.
    if (this.totalItems > 0) {
      this.lastRequestSignature = '';
      this.load();
      return true;
    }

    this.isLoading = false;
    return false;
  }
}