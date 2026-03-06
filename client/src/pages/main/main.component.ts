import { Component, HostListener, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';

// Services
import { EventService } from '../../services/event/event.service';
import { RestService } from '../../services/rest/rest.service';
import { AuthService } from '../../services/auth/auth.service';
import { SupportRealtimeService } from '../../services/support-realtime/support-realtime.service';

// Data
import { data } from '../../memory/global-data';

// Components
import { DiscoverComponent } from './components/discover-area/parent/discover-area.component';
import { ProductPageComponent } from './components/product-page/product-page.component';
import { SignUserComponent } from './components/sign-user/sign-user.component';
import { UserCartComponent } from './components/user-cart/user-cart.component';
import { UserProfileComponent } from './components/user-profile/user-profile.component';
import { OrderTrackerComponent } from './components/order-tracker/order-tracker.component';
import { IChatMessage } from '../../interfaces/IChatMessage';
import { ISupportRealtimeEvent } from '../../interfaces/support/ISupportRealtimeEvent';

@Component({
  selector: 'app-main',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    DiscoverComponent,
    ProductPageComponent,
    SignUserComponent,
    UserCartComponent,
    UserProfileComponent,
    OrderTrackerComponent,
  ],
  templateUrl: './main.component.html',
  styleUrl: './main.component.css',
})
export class MainComponent implements OnInit, OnDestroy {
  // Page State
  isItemPageOpened = false;
  isLoginButtonClicked = false;
  isOrderButtonClicked = false;
  isProfileButtonClicked = false;
  isCartComponentOpened = false;

  // Auth State
  isUserSigned = false;
  loginText = 'Uye Ol veya Giris Yap';

  // Data State
  orderIds: number[] = [];
  isMenuOpen = false;

  // Support Chat State
  isSupportChatOpen = false;
  supportMessages: IChatMessage[] = [];
  supportMessageInput = '';
  isSupportLoading = false;
  isSupportSending = false;
  supportErrorMessage = '';
  private supportPollingIntervalId: ReturnType<typeof setInterval> | null = null;
  private currentUserMail = '';
  private supportRealtimeSub: Subscription | null = null;

  constructor(
    private eventManager: EventService,
    private restService: RestService,
    private authService: AuthService,
    private supportRealtimeService: SupportRealtimeService
  ) {
    this.eventManager.on('openItemPage', (categoryId: number) => {
      data.currentSelectedCategoryId = categoryId;
      this.setItemPageOpened(true);
    });

    this.checkAuthStatus();

    this.restService.getAllOrderIds().subscribe({
      next: (ids: number[]) => (this.orderIds = ids),
      error: (err) => console.error('Order fetch failed', err),
    });
  }

  ngOnInit(): void {
    this.updateCurrentUserMailFromToken();
    this.supportRealtimeService.connect();
    this.supportRealtimeSub = this.supportRealtimeService.events$.subscribe(
      (event: ISupportRealtimeEvent) => {
        if (!this.isSupportChatOpen || !this.currentUserMail) {
          return;
        }

        if (event.userMail === this.currentUserMail) {
          this.loadSupportMessages();
        }
      }
    );
  }

  ngOnDestroy(): void {
    this.stopSupportMessagePolling();
    this.supportRealtimeSub?.unsubscribe();
  }

  private checkAuthStatus() {
    if (this.authService.isLoggedIn()) {
      this.loginText = 'Can';
      this.isUserSigned = true;
    } else {
      this.isUserSigned = false;
      this.loginText = 'Uye Ol veya Giris Yap';
    }
  }

  private updateCurrentUserMailFromToken() {
    const token = this.authService.getToken();
    if (!token) {
      this.currentUserMail = '';
      return;
    }

    const decoded = this.authService.decodeToken(token);
    this.currentUserMail = decoded?.sub ?? '';
  }

  public toggleMenu(event: Event) {
    event.stopPropagation();
    this.isMenuOpen = !this.isMenuOpen;
  }

  @HostListener('document:click')
  public closeMenu() {
    this.isMenuOpen = false;
  }

  public isUserLoggedIn() {
    return this.isUserSigned;
  }

  public setItemPageOpened(value: boolean) {
    this.isItemPageOpened = value;
  }

  public hasItemPageOpened(): boolean {
    return this.isItemPageOpened;
  }

  public openLoginComponent() {
    this.isLoginButtonClicked = true;
  }

  public closeLoginComponent() {
    this.isLoginButtonClicked = false;
  }

  public loginUser() {
    this.isUserSigned = true;
    this.loginText = 'Can';
    this.isLoginButtonClicked = false;
    this.updateCurrentUserMailFromToken();
  }

  public logoutUser() {
    this.authService.logout();
    this.isUserSigned = false;
    this.isMenuOpen = false;
    this.loginText = 'Uye Ol veya Giris Yap';
    this.setItemPageOpened(false);
    this.isSupportChatOpen = false;
    this.supportMessages = [];
    this.supportErrorMessage = '';
    this.currentUserMail = '';
    this.stopSupportMessagePolling();
  }

  public openCartComponent() {
    this.isCartComponentOpened = true;
    this.isMenuOpen = false;
  }

  public closeCartComponent() {
    this.isCartComponentOpened = false;
  }

  public openProfileComponent() {
    this.isProfileButtonClicked = true;
    this.isMenuOpen = false;
  }

  public closeProfileComponent() {
    this.isProfileButtonClicked = false;
  }

  public openOrderComponent() {
    if (!this.isUserSigned) {
      this.openLoginComponent();
      return;
    }
    if (this.orderIds.length === 0) {
      alert('Hic siparisiniz yok');
      return;
    }
    this.isOrderButtonClicked = true;
  }

  public closeOrderTrackerComponent() {
    this.isOrderButtonClicked = false;
  }

  public toggleSupportChat() {
    if (!this.isUserSigned) {
      this.openLoginComponent();
      return;
    }

    this.isSupportChatOpen = !this.isSupportChatOpen;

    if (this.isSupportChatOpen) {
      this.loadSupportMessages();
      this.startSupportMessagePolling();
      return;
    }

    this.stopSupportMessagePolling();
  }

  public sendSupportMessage() {
    const message = this.supportMessageInput.trim();
    if (!message || this.isSupportSending) {
      return;
    }

    this.isSupportSending = true;
    this.restService.sendSupportMessage(message).subscribe({
      next: () => {
        this.supportMessageInput = '';
        this.isSupportSending = false;
        this.loadSupportMessages();
      },
      error: (err) => {
        this.isSupportSending = false;
        this.supportErrorMessage =
          typeof err?.error === 'string' && err.error
            ? err.error
            : 'Mesaj gonderilemedi. Lutfen tekrar deneyin.';
      },
    });
  }

  private loadSupportMessages() {
    this.isSupportLoading = true;
    this.supportErrorMessage = '';

    this.restService.getSupportMessages().subscribe({
      next: (messages: IChatMessage[]) => {
        this.supportMessages = messages;
        this.isSupportLoading = false;
      },
      error: (err) => {
        this.isSupportLoading = false;
        this.supportErrorMessage =
          typeof err?.error === 'string' && err.error
            ? err.error
            : 'Mesajlar yuklenemedi. Lutfen tekrar deneyin.';
      },
    });
  }

  private startSupportMessagePolling() {
    this.stopSupportMessagePolling();
    this.supportPollingIntervalId = setInterval(() => {
      if (this.isSupportChatOpen) {
        this.loadSupportMessages();
      }
    }, 5000);
  }

  private stopSupportMessagePolling() {
    if (this.supportPollingIntervalId) {
      clearInterval(this.supportPollingIntervalId);
      this.supportPollingIntervalId = null;
    }
  }
}
