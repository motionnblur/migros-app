import { Component, OnDestroy, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { combineLatest, Subscription } from 'rxjs';

import {
  CATEGORY_CATALOG,
  CategoryDefinition,
  filterCategoriesByQuery,
} from '../../../../../memory/category-catalog';
import { scrollToFragment } from '../../../helpers/scroll-to-fragment';
import { CategoryButtonComponent } from '../child/category-button/category-button.component';
import { LandingHeroComponent } from '../child/landing-hero/landing-hero.component';

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
}
