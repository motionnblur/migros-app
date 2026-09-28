import { Component, OnDestroy, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription } from 'rxjs';

import {
  CATEGORY_CATALOG,
  CategoryDefinition,
  filterCategoriesByQuery,
} from '../../../../../memory/category-catalog';
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

  private querySub: Subscription | null = null;
  private fragmentSub: Subscription | null = null;

  constructor(
    private route: ActivatedRoute,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.querySub = this.route.queryParamMap.subscribe((params) => {
      this.searchQuery = (params.get('q') ?? '').trim();
      this.visibleCategories = filterCategoriesByQuery(
        CATEGORY_CATALOG,
        this.searchQuery
      );
    });

    this.fragmentSub = this.route.fragment.subscribe((fragment) => {
      if (fragment) {
        this.scrollToFragment(fragment);
      }
    });
  }

  ngOnDestroy(): void {
    this.querySub?.unsubscribe();
    this.fragmentSub?.unsubscribe();
  }

  public clearSearch(): void {
    this.router.navigate(['/']);
  }

  private scrollToFragment(fragment: string): void {
    requestAnimationFrame(() => {
      const target = document.getElementById(fragment);
      if (!target) {
        return;
      }

      const prefersReducedMotion = window.matchMedia(
        '(prefers-reduced-motion: reduce)'
      ).matches;

      target.scrollIntoView({
        behavior: prefersReducedMotion ? 'auto' : 'smooth',
        block: 'start',
      });
    });
  }
}
