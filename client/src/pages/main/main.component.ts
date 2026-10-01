import { Component, OnDestroy, OnInit } from '@angular/core';
import { ActivatedRoute, Router, RouterOutlet } from '@angular/router';
import { Subject, Subscription, takeUntil } from 'rxjs';

import { AuthService } from '../../services/auth/auth.service';
import { RestService } from '../../services/rest/rest.service';
import { SupportRealtimeService } from '../../services/support-realtime/support-realtime.service';
import { IChatMessage } from '../../interfaces/IChatMessage';
import { ISupportRealtimeEvent } from '../../interfaces/support/ISupportRealtimeEvent';
import {
  SiteFooterAction,
  SiteFooterComponent,
} from './components/site-footer/site-footer.component';
import {
  SiteHeaderAction,
  SiteHeaderComponent,
} from './components/site-header/site-header.component';
import { SupportFabComponent } from './components/support-fab/support-fab.component';

@Component({
  selector: 'app-main',
  standalone: true,
  imports: [RouterOutlet, SiteHeaderComponent, SiteFooterComponent, SupportFabComponent],
  templateUrl: './main.component.html',
  styleUrl: './main.component.css',
})
export class MainComponent implements OnInit, OnDestroy {
  isUserSigned = false;
  searchQuery = '';
  /**
   * Whether a dialog is currently open on the `modal` outlet.
   *
   * <p>Driven by the outlet's own activate/deactivate events rather than by
   * reading the URL, so it is a fact about what is on screen and not a guess
   * about a route string. It exists for one decision - withdrawing the support
   * launcher while a dialog is up, because that launcher is a control on the page
   * the dialog is covering.
   */
  isModalOpen = false;

  private authStatusSub: Subscription | null = null;
  private supportRealtimeSub: Subscription | null = null;
  private queryParamSub: Subscription | null = null;
  private lastSupportPopupUserMail = '';
  private readonly destroy$ = new Subject<void>();

  constructor(
    private authService: AuthService,
    private restService: RestService,
    private supportRealtimeService: SupportRealtimeService,
    private router: Router,
    private route: ActivatedRoute
  ) {}

  ngOnInit(): void {
    this.authStatusSub = this.authService.userLoggedIn$.subscribe(() => {
      this.checkAuthStatus();
    });

    this.queryParamSub = this.route.queryParamMap.subscribe((params) => {
      this.searchQuery = (params.get('q') ?? '').trim();
    });

    this.supportRealtimeSub = this.supportRealtimeService.events$.subscribe(
      (event: ISupportRealtimeEvent) => {
        if (!this.isUserSigned) {
          return;
        }

        const currentUserMail = (this.authService.getUserMail() || '').trim().toLowerCase();
        const eventUserMail = (event?.userMail || '').trim().toLowerCase();
        if (!currentUserMail || currentUserMail !== eventUserMail) {
          return;
        }

        if (
          event.type === 'SUPPORT_MESSAGE_CREATED' &&
          event.sender === 'MANAGEMENT'
        ) {
          if (!this.isSupportChatOpen()) {
            this.openModal('support');
          }
        }
      }
    );

    this.authService.refreshUserSession().pipe(takeUntil(this.destroy$)).subscribe();
  }

  ngOnDestroy(): void {
    this.authStatusSub?.unsubscribe();
    this.queryParamSub?.unsubscribe();
    this.supportRealtimeSub?.unsubscribe();
    this.supportRealtimeService.disconnect();
    this.destroy$.next();
    this.destroy$.complete();
  }

  private checkAuthStatus() {
    if (this.authService.isLoggedIn()) {
      const userMail = this.authService.getUserMail();
      this.isUserSigned = true;

      if (userMail) {
        const normalizedUserMail = userMail.trim().toLowerCase();
        this.supportRealtimeService.connect(normalizedUserMail);
        if (this.lastSupportPopupUserMail !== normalizedUserMail) {
          this.lastSupportPopupUserMail = normalizedUserMail;
          this.checkForPendingSupportMessages(normalizedUserMail);
        }
      }
    } else {
      this.isUserSigned = false;
      this.supportRealtimeService.disconnect();
      this.lastSupportPopupUserMail = '';
    }
  }

  public isUserLoggedIn() {
    return this.isUserSigned;
  }

  /**
   * A dialog has entered the `modal` outlet.
   *
   * <p>Fired by the outlet itself, so it covers every modal - cart, login,
   * profile, order tracker, order history and the support panel - without this
   * component having to know the list, and therefore without a new modal being
   * able to arrive with the launcher still floating over it.
   */
  public onModalActivated(): void {
    this.isModalOpen = true;
  }

  /**
   * The last dialog has left the `modal` outlet.
   *
   * <p>Set unconditionally rather than counted: one modal replaces another on the
   * same outlet, and a counter that survives a `cart` -> `support` hand-over is a
   * counter that is one too high forever, which would hide the launcher for the
   * rest of the session. The outlet emits `deactivate` before it emits the
   * replacement's `activate`, so the pair still ends on the truth.
   */
  public onModalDeactivated(): void {
    this.isModalOpen = false;
  }

  public handleHeaderAction(action: SiteHeaderAction): void {
    switch (action) {
      case 'login':
        this.openLoginComponent();
        break;
      case 'cart':
        this.openCartComponent();
        break;
      case 'profile':
        this.openProfileComponent();
        break;
      case 'orderTracker':
        this.openOrderComponent();
        break;
      case 'orderHistory':
        this.openOrderHistoryComponent();
        break;
      case 'logout':
        this.logoutUser();
        break;
    }
  }

  public handleFooterAction(action: SiteFooterAction): void {
    if (action === 'orderTracker') {
      this.openOrderComponent();
      return;
    }

    if (action === 'support') {
      this.openSupportChat();
    }
  }

  /**
   * The header's primary search is a product search, so it navigates to
   * `/search`. `/?q=` stays the landing page's own category filter, so an old
   * deep link still renders rather than abruptly breaking; the discover page
   * offers the product search for the same term.
   *
   * A blank submission clears the search and returns home rather than opening an
   * unfiltered results page: clearing the box is a request for the catalogue the
   * customer was looking at before, which is what the home page is.
   */
  public handleSearch(term: string): void {
    const trimmedTerm = (term || '').trim();

    if (!trimmedTerm) {
      this.router.navigate(['/']);
      return;
    }

    this.router.navigate(['/search'], { queryParams: { q: trimmedTerm } });
  }

  public openLoginComponent() {
    this.openModal('login');
  }

  public logoutUser() {
    this.authService.logout();
    this.router.navigate([{ outlets: { modal: null } }], {
      relativeTo: this.route,
    });
  }

  public openCartComponent() {
    if (!this.isUserSigned) {
      this.openLoginComponent();
      return;
    }

    this.openModal('cart');
  }

  public openProfileComponent() {
    if (!this.isUserSigned) {
      this.openLoginComponent();
      return;
    }

    this.openModal('profile');
  }

  public openOrderComponent() {
    if (!this.isUserSigned) {
      this.openLoginComponent();
      return;
    }

    this.openModal('order-tracker');
  }

  public openOrderHistoryComponent() {
    if (!this.isUserSigned) {
      this.openLoginComponent();
      return;
    }

    this.openModal('order-history');
  }

  public openSupportChat() {
    if (!this.isUserSigned) {
      this.openLoginComponent();
      return;
    }

    this.openModal('support');
  }

  private openModal(path: string) {
    this.router.navigate([{ outlets: { modal: [path] } }], {
      relativeTo: this.route,
    });
  }

  private isSupportChatOpen(): boolean {
    return this.router.url.includes('(modal:support)');
  }

  private checkForPendingSupportMessages(userMail: string) {
    const normalizedUserMail = (userMail || '').trim().toLowerCase();
    if (!normalizedUserMail) {
      return;
    }

    this.restService.getSupportMessages().pipe(takeUntil(this.destroy$)).subscribe({
      next: (messages: IChatMessage[]) => {
        const latestManagementId = this.getLatestManagementMessageId(messages);
        const lastSeenId = this.getLastSeenManagementMessageId(normalizedUserMail);
        if (latestManagementId > lastSeenId && !this.isSupportChatOpen()) {
          this.openModal('support');
        }
      },
    });
  }

  private getLatestManagementMessageId(messages: IChatMessage[]): number {
    return messages
      .filter((message) => message.sender === 'MANAGEMENT')
      .reduce((maxId, message) => Math.max(maxId, message.id), 0);
  }

  private getLastSeenManagementMessageId(userMail: string): number {
    const rawValue = localStorage.getItem(this.getLastSeenKey(userMail));
    if (!rawValue) {
      return 0;
    }

    const parsed = Number(rawValue);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  private getLastSeenKey(userMail: string): string {
    return `support:lastSeenManagementMessageId:${userMail}`;
  }
}
