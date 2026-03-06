import { Component, HostListener, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

// Services
import { EventService } from '../../services/event/event.service';
import { RestService } from '../../services/rest/rest.service';
import { AuthService } from '../../services/auth/auth.service';

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
export class MainComponent implements OnDestroy {
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

  constructor(
    private eventManager: EventService,
    private restService: RestService,
    private authService: AuthService
  ) {
    // Listen for category navigation
    this.eventManager.on('openItemPage', (categoryId: number) => {
      data.currentSelectedCategoryId = categoryId;
      this.setItemPageOpened(true);
    });

    // Initial Auth Check
    this.checkAuthStatus();

    // Load Orders
    this.restService.getAllOrderIds().subscribe({
      next: (ids: number[]) => (this.orderIds = ids),
      error: (err) => console.error('Order fetch failed', err),
    });
  }

  ngOnDestroy(): void {
    this.stopSupportMessagePolling();
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

  // --- UI CONTROLS ---

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

  // --- NAVIGATION & MODALS ---

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

  // --- SUPPORT CHAT ---

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
      error: () => {
        this.isSupportSending = false;
        this.supportErrorMessage =
          'Mesaj gonderilemedi. Lutfen tekrar deneyin.';
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
      error: () => {
        this.isSupportLoading = false;
        this.supportErrorMessage =
          'Mesajlar yuklenemedi. Lutfen tekrar deneyin.';
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
