import { Component, EventEmitter, Input, OnChanges, Output } from '@angular/core';
import { CommonModule } from '@angular/common';

export type PageSwitcherEntry = number | 'gap';

@Component({
  selector: 'app-product-page-switcher',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './product-page-switcher.component.html',
  styleUrl: './product-page-switcher.component.css',
})
export class ProductPageSwitcherComponent implements OnChanges {
  @Input() currentPage = 1;
  @Input() pageCount = 1;

  @Output() pageChange = new EventEmitter<number>();

  normalizedCurrentPage = 1;
  normalizedPageCount = 1;
  visiblePages: PageSwitcherEntry[] = [];

  ngOnChanges(): void {
    this.normalizedPageCount = Math.max(1, Math.floor(this.pageCount) || 1);
    this.normalizedCurrentPage = Math.min(
      Math.max(1, Math.floor(this.currentPage) || 1),
      this.normalizedPageCount,
    );
    this.visiblePages = this.buildVisiblePages(
      this.normalizedCurrentPage,
      this.normalizedPageCount,
    );
  }

  get canGoPrevious(): boolean {
    return this.normalizedCurrentPage > 1;
  }

  get canGoNext(): boolean {
    return this.normalizedCurrentPage < this.normalizedPageCount;
  }

  get lastPage(): number {
    return this.normalizedPageCount;
  }

  public selectPage(page: number): void {
    if (!Number.isFinite(page)) {
      return;
    }

    const target = Math.floor(page);
    if (target < 1 || target > this.normalizedPageCount) {
      return;
    }

    if (target === this.normalizedCurrentPage) {
      return;
    }

    this.pageChange.emit(target);
  }

  public selectFirst(): void {
    this.selectPage(1);
  }

  public selectPrevious(): void {
    this.selectPage(this.normalizedCurrentPage - 1);
  }

  public selectNext(): void {
    this.selectPage(this.normalizedCurrentPage + 1);
  }

  public selectLast(): void {
    this.selectPage(this.normalizedPageCount);
  }

  public isCurrentPage(page: number): boolean {
    return page === this.normalizedCurrentPage;
  }

  private buildVisiblePages(
    currentPage: number,
    pageCount: number,
  ): PageSwitcherEntry[] {
    if (pageCount <= 7) {
      return Array.from({ length: pageCount }, (_unused, index) => index + 1);
    }

    const wanted = new Set<number>([
      1,
      pageCount,
      currentPage - 1,
      currentPage,
      currentPage + 1,
    ]);

    const sorted = [...wanted]
      .filter((page) => page >= 1 && page <= pageCount)
      .sort((a, b) => a - b);

    const entries: PageSwitcherEntry[] = [];
    sorted.forEach((page, index) => {
      if (index > 0 && page - sorted[index - 1] > 1) {
        entries.push('gap');
      }
      entries.push(page);
    });

    return entries;
  }
}
