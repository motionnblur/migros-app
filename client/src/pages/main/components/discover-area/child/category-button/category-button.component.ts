import { Component, Input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { environment } from '../../../../../../environments/environment';
import {
  staticImageUrl,
  supabaseImageUrl,
} from '../../../../../../app/config/supabase-assets';

@Component({
  selector: 'app-category-button',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './category-button.component.html',
  styleUrl: './category-button.component.css',
})
export class CategoryButtonComponent {
  readonly staticImageUrl = staticImageUrl;
  @Input() image: string = '/discover-items/meyve.png';
  @Input() name: string = 'Name';
  @Input() categoryId!: number;

  public get categoryLink(): (string | number)[] {
    return ['/category', this.categoryId];
  }

  public onImageError(event: Event) {
    if (!environment.production) {
      return;
    }

    const target = event.target as HTMLImageElement | null;
    if (!target || target.dataset['supabaseFallbackTried'] === '1') {
      return;
    }

    const imagePath = (this.image || '').replace(/^\/+/, '');
    if (!imagePath.startsWith('discover-items/')) {
      return;
    }

    target.dataset['supabaseFallbackTried'] = '1';
    const fallbackPath = imagePath.replace(/^discover-items\//, '');
    target.src = supabaseImageUrl(fallbackPath);
  }
}
