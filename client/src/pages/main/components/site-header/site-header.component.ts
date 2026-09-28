import {
  Component,
  ElementRef,
  EventEmitter,
  HostListener,
  Input,
  OnChanges,
  OnDestroy,
  OnInit,
  Output,
  SimpleChanges,
  ViewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';

import { staticImageUrl } from '../../../../app/config/supabase-assets';
import { AuthService } from '../../../../services/auth/auth.service';

export type SiteHeaderAction =
  | 'login'
  | 'cart'
  | 'profile'
  | 'orderTracker'
  | 'orderHistory'
  | 'logout';

@Component({
  selector: 'app-site-header',
  standalone: true,
  imports: [FormsModule, RouterLink],
  templateUrl: './site-header.component.html',
  styleUrl: './site-header.component.css',
})
export class SiteHeaderComponent implements OnInit, OnChanges, OnDestroy {
  @Input() searchQuery = '';
  @Output() action = new EventEmitter<SiteHeaderAction>();
  @Output() searchSubmitted = new EventEmitter<string>();

  readonly staticImageUrl = staticImageUrl;

  searchTerm = '';
  isLoggedIn = false;
  accountLabel = 'Üye Ol veya Giriş Yap';
  isAccountMenuOpen = false;

  @ViewChild('accountToggle', { static: false })
  private accountToggle: ElementRef<HTMLButtonElement> | null = null;

  private authSub: Subscription | null = null;

  constructor(private authService: AuthService) {}

  ngOnInit(): void {
    this.authSub = this.authService.userLoggedIn$.subscribe(() =>
      this.syncAccountState()
    );
  }

  ngOnChanges(changes: SimpleChanges): void {
    const searchChange = changes['searchQuery'];
    if (!searchChange) {
      return;
    }

    const nextValue = searchChange.currentValue ?? '';
    if (nextValue !== this.searchTerm) {
      this.searchTerm = nextValue;
    }
  }

  ngOnDestroy(): void {
    this.authSub?.unsubscribe();
  }

  public submitSearch(): void {
    this.searchSubmitted.emit(this.searchTerm.trim());
  }

  public toggleAccountMenu(event: Event): void {
    event.stopPropagation();
    this.isAccountMenuOpen = !this.isAccountMenuOpen;
  }

  public selectAction(action: SiteHeaderAction): void {
    this.isAccountMenuOpen = false;
    this.action.emit(action);
  }

  @HostListener('document:click')
  public closeAccountMenu(): void {
    this.isAccountMenuOpen = false;
  }

  @HostListener('document:keydown.escape', ['$event'])
  public closeAccountMenuOnEscape(event: KeyboardEvent): void {
    if (!this.isAccountMenuOpen) {
      return;
    }

    event.preventDefault();
    this.isAccountMenuOpen = false;
    this.accountToggle?.nativeElement.focus();
  }

  private syncAccountState(): void {
    this.isLoggedIn = this.authService.isLoggedIn();
    const userMail = (this.authService.getUserMail() || '').trim();
    this.accountLabel = this.isLoggedIn
      ? userMail || 'Hesabım'
      : 'Üye Ol veya Giriş Yap';

    if (!this.isLoggedIn) {
      this.isAccountMenuOpen = false;
    }
  }
}
