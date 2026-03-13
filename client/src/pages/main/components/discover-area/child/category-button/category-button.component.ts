import { Component, Input } from '@angular/core';
import { Router } from '@angular/router';
import { supabaseImageUrl } from '../../../../../../app/config/supabase-assets';

@Component({
  selector: 'app-category-button',
  standalone: true,
  imports: [],
  templateUrl: './category-button.component.html',
  styleUrl: './category-button.component.css',
})
export class CategoryButtonComponent {
  readonly supabaseImageUrl = supabaseImageUrl;
  @Input() image: string = '/discover-items/meyve.png';
  @Input() name: string = 'Name';
  @Input() categoryId!: number;

  constructor(private router: Router) {}

  openItemPage() {
    this.router.navigate(['/category', this.categoryId]);
  }

  onImageError(event: Event) {
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
    target.src = this.supabaseImageUrl(fallbackPath);
  }
}
