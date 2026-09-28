import { Component, OnDestroy, OnInit } from '@angular/core';
import { ActivatedRoute, Router, RouterOutlet } from '@angular/router';
import { Subscription } from 'rxjs';

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

  private authStatusSub: Subscription | null = null;
  private supportRealtimeSub: Subscription | null = null;
  private queryParamSub: Subscription | null = null;
  private lastSupportPopupUserMail = '';

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

    this.authService.refreshUserSession().subscribe();
  }

  ngOnDestroy(): void {
    this.authStatusSub?.unsubscribe();
    this.queryParamSub?.unsubscribe();
    this.supportRealtimeSub?.unsubscribe();
    this.supportRealtimeService.disconnect();
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

  public handleSearch(term: string): void {
    const trimmedTerm = (term || '').trim();
    this.router.navigate(['/'], {
      queryParams: trimmedTerm ? { q: trimmedTerm } : {},
      fragment: 'categories',
    });
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

    this.restService.getSupportMessages().subscribe({
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
