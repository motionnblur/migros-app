import { Component, OnDestroy, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { combineLatest, Subscription } from 'rxjs';

import {
  CATEGORY_CATALOG,
  CategoryDefinition,
  filterCategoriesByQuery,
} from '../../../../../memory/category-catalog';
import { buildListingQueryParams } from '../../../helpers/catalog-listing-state';
import { scrollToFragment } from '../../../helpers/scroll-to-fragment';
import { CategoryButtonComponent } from '../child/category-button/category-button.component';
import { LandingHeroComponent } from '../child/landing-hero/landing-hero.component';

/**
 * The landing page, with its own category-name filter.
 *
 * `/?q=` stays exactly what it was - categories matching the term - so a deep
 * link that predates the product search still renders. The header's search moved
 * to `/search`, which is why this page now also offers the same term as a
 * product search instead of pretending the customer asked about categories.
 */
@Component({
  selector: 'app-discover',
  standalone: true,
  imports: [CategoryButtonComponent, LandingHeroComponent],
  templateUrl: './discover-area.component.html',
  styleUrl: './discover-area.component.css',
})
export class DiscoverComponent implements OnInit, OnDestroy {
  searchQuery = '';
  visibleCategories: CategoryDefinition[] = [...CATEGORY_CATALOG];
  productSearchQueryParams: Record<string, string> = {};

  private routeSub: Subscription | null = null;

  constructor(
    private route: ActivatedRoute,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.routeSub = combineLatest([
      this.route.queryParamMap,
      this.route.fragment,
    ]).subscribe(([params, fragment]) => {
      this.searchQuery = (params.get('q') ?? '').trim();
      this.visibleCategories = filterCategoriesByQuery(
        CATEGORY_CATALOG,
        this.searchQuery
      );
      this.productSearchQueryParams = this.searchQuery
        ? buildListingQueryParams({ q: this.searchQuery })
        : {};

      if (fragment) {
        scrollToFragment(fragment);
      }
    });
  }

  ngOnDestroy(): void {
    this.routeSub?.unsubscribe();
  }

  public clearSearch(): void {
    this.router.navigate(['/']);
  }

  public searchProducts(): void {
    const trimmedTerm = (this.searchQuery || '').trim();
    if (!trimmedTerm) {
      return;
    }

    this.router.navigate(['/search'], {
      queryParams: buildListingQueryParams({ q: trimmedTerm }),
    });
  }
}