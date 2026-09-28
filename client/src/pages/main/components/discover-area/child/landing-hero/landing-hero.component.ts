import { Component } from '@angular/core';

import { staticImageUrl } from '../../../../../../app/config/supabase-assets';

@Component({
  selector: 'app-landing-hero',
  standalone: true,
  templateUrl: './landing-hero.component.html',
  styleUrl: './landing-hero.component.css',
})
export class LandingHeroComponent {
  readonly staticImageUrl = staticImageUrl;
  readonly fruitsImage = staticImageUrl('/discover-items/meyve.png');
  readonly breakfastImage = staticImageUrl('/discover-items/stkvlt.png');
  readonly bakeryImage = staticImageUrl('/discover-items/firin.png');

  public scrollToCategories(): void {
    const categoriesSection = document.getElementById('categories');
    if (!categoriesSection) {
      return;
    }

    const prefersReducedMotion = window.matchMedia(
      '(prefers-reduced-motion: reduce)'
    ).matches;

    categoriesSection.scrollIntoView({
      behavior: prefersReducedMotion ? 'auto' : 'smooth',
      block: 'start',
    });
    categoriesSection.focus({ preventScroll: true });
  }
}
