import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, OnChanges, Output } from '@angular/core';

import { IProductPreview } from '../../../../interfaces/IProductPreview';
import { ProductSearchAvailability } from '../../../../interfaces/IProductSearchQuery';
import {
  LISTING_AVAILABILITY_OPTIONS,
  LISTING_SORT_OPTIONS,
  ListingFilterChip,
  ListingFilterState,
  buildListingFilterChips,
  hasActiveListingFilters,
  normalizeListingSort,
  priceRangeError,
} from '../../helpers/catalog-listing-state';
import { ProductPageSwitcherComponent } from '../product-page-switcher/product-page-switcher.component';
import { ProductPreviewComponent } from '../product-preview/product-preview.component';

/** What a listing asks for after the customer touched a control. */
export interface ProductListingRequest {
  filters: ListingFilterState;
  /**
   * Always 1 for a filter or sort change. A narrowed result set usually has
   * fewer pages, so keeping the old page number would land on an empty page the
   * customer did not ask for.
   */
  page: number;
}

/**
 * The one presentation every customer product listing uses.
 *
 * The category page and the search results page differ only in what they load
 * and what they title the result; the filter toolbar, the applied-filter chips,
 * the grid, the paginator and the loading/error/empty states are here. Two
 * hand-written copies of this is how a listing ends up paging the wrong list or
 * reporting a count that no longer matches its rows.
 *
 * Every control is a real form control that reports a change to the host, and the
 * host is what turns that into a URL. Nothing here filters the rows it was
 * given: filtering, counting and sorting are the server's answers, so a customer
 * who narrows the list gets a genuinely narrowed list rather than a narrowed
 * page of the last broad one.
 */
@Component({
  selector: 'app-product-listing',
  standalone: true,
  imports: [CommonModule, ProductPreviewComponent, ProductPageSwitcherComponent],
  templateUrl: './product-listing.component.html',
  styleUrl: './product-listing.component.css',
})
export class ProductListingComponent implements OnChanges {
  @Input() heading = '';
  @Input() selectionLabel = '';
  @Input() totalItems = 0;
  @Input() items: IProductPreview[] = [];
  @Input() page = 1;
  @Input() pageCount = 1;
  @Input() filters: ListingFilterState = {
    availability: 'ALL',
    minPrice: '',
    maxPrice: '',
    discountedOnly: false,
    sort: 'DEFAULT',
  };
  @Input() isLoading = false;
  @Input() hasLoadError = false;
  @Input() emptyTitle = 'Bu seçimde ürün bulunamadı';
  @Input() emptyText = '';
  /**
   * The category a card links into, or `null` for a search result, whose product
   * may live in any category and therefore has no category detail route to point
   * at.
   */
  @Input() linkCategoryId: number | null = null;
  /** Rendered as a removable chip when non-empty. */
  @Input() subCategoryName = '';

  @Output() requestChange = new EventEmitter<ProductListingRequest>();
  @Output() subCategoryCleared = new EventEmitter<void>();
  @Output() retryRequested = new EventEmitter<void>();

  readonly availabilityOptions = LISTING_AVAILABILITY_OPTIONS;
  readonly sortOptions = LISTING_SORT_OPTIONS;

  /** Local draft so typing a bound does not refetch on every keystroke. */
  minPriceDraft = '';
  maxPriceDraft = '';
  priceError: string | null = null;

  chips: ListingFilterChip[] = [];

  ngOnChanges(): void {
    this.chips = buildListingFilterChips(this.filters);

    // Only adopt the URL's bounds while the customer is not mid-edit: overwriting
    // a field they are typing in is how a draft becomes unreadable.
    if (this.priceError === null) {
      this.minPriceDraft = this.filters.minPrice ?? '';
      this.maxPriceDraft = this.filters.maxPrice ?? '';
    }
  }

  get hasActiveFilters(): boolean {
    return hasActiveListingFilters(this.filters);
  }

  get hasChips(): boolean {
    return this.chips.length > 0;
  }

  get hasSubCategoryChip(): boolean {
    return (this.subCategoryName ?? '').trim() !== '';
  }

  get resultSummary(): string {
    const total = Math.max(0, Math.trunc(this.totalItems ?? 0));
    return `${total} ürün`;
  }

  public isAvailabilitySelected(value: ProductSearchAvailability): boolean {
    return this.filters.availability === value;
  }

  public selectAvailability(value: ProductSearchAvailability): void {
    if (value === this.filters.availability) {
      return;
    }

    this.emitFilters({ availability: value });
  }

  /**
   * A `<select>` hands back a plain string, so the value is normalized here rather
   * than cast: a hand-edited option or a stale DOM value becomes the default
   * ordering instead of reaching the endpoint's case-sensitive enum.
   */
  public selectSort(rawValue: string): void {
    const value = normalizeListingSort(rawValue);
    if (value === this.filters.sort) {
      return;
    }

    this.emitFilters({ sort: value });
  }

  public toggleDiscountedOnly(): void {
    this.emitFilters({ discountedOnly: !this.filters.discountedOnly });
  }

  /**
   * Applies the price bounds.
   *
   * Refuses an inverted pair instead of forwarding it: the endpoint answers 400
   * for `minPrice > maxPrice`, and a listing that answered with an error panel
   * for a value the customer typed would be reporting their typo as a fault.
   */
  public applyPriceBounds(): void {
    const error = priceRangeError(this.minPriceDraft, this.maxPriceDraft);
    if (error !== null) {
      this.priceError = error;
      return;
    }

    this.priceError = null;
    this.emitFilters({
      minPrice: this.minPriceDraft,
      maxPrice: this.maxPriceDraft,
    });
  }

  public clearPriceBounds(): void {
    this.priceError = null;
    this.minPriceDraft = '';
    this.maxPriceDraft = '';
    this.emitFilters({ minPrice: '', maxPrice: '' });
  }

  public removeChip(chip: ListingFilterChip): void {
    switch (chip.key) {
      case 'availability':
        this.emitFilters({ availability: 'ALL' });
        return;
      case 'price':
        this.clearPriceBounds();
        return;
      case 'discounted':
        this.emitFilters({ discountedOnly: false });
        return;
      case 'sort':
        this.emitFilters({ sort: 'DEFAULT' });
        return;
    }
  }

  public clearAllFilters(): void {
    this.priceError = null;
    this.minPriceDraft = '';
    this.maxPriceDraft = '';
    this.emitFilters({
      availability: 'ALL',
      minPrice: '',
      maxPrice: '',
      discountedOnly: false,
      sort: 'DEFAULT',
    });
  }

  public selectPage(page: number): void {
    this.requestChange.emit({ filters: this.filters, page });
  }

  public clearSubCategory(): void {
    this.subCategoryCleared.emit();
  }

  public retry(): void {
    this.retryRequested.emit();
  }

  private emitFilters(changes: Partial<ListingFilterState>): void {
    this.requestChange.emit({
      filters: { ...this.filters, ...changes },
      page: 1,
    });
  }
}