import { Component, EventEmitter, Output } from '@angular/core';
import { RouterLink } from '@angular/router';

import { staticImageUrl } from '../../../../app/config/supabase-assets';

export type SiteFooterAction = 'orderTracker' | 'support';

@Component({
  selector: 'app-site-footer',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './site-footer.component.html',
  styleUrl: './site-footer.component.css',
})
export class SiteFooterComponent {
  readonly staticImageUrl = staticImageUrl;

  @Output() action = new EventEmitter<SiteFooterAction>();

  public selectAction(action: SiteFooterAction): void {
    this.action.emit(action);
  }
}
