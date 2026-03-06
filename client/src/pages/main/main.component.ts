import {Component, HostListener} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';

// Services
import {EventService} from '../../services/event/event.service';
import {RestService} from '../../services/rest/rest.service';
import {AuthService} from '../../services/auth/auth.service';

// Data
import {data} from '../../memory/global-data';

// Components
import {DiscoverComponent} from './components/discover-area/parent/discover-area.component';
import {ProductPageComponent} from './components/product-page/product-page.component';
import {SignUserComponent} from './components/sign-user/sign-user.component';
import {UserCartComponent} from './components/user-cart/user-cart.component';
import {UserProfileComponent} from './components/user-profile/user-profile.component';
import {OrderTrackerComponent} from './components/order-tracker/order-tracker.component';

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
    OrderTrackerComponent
  ],
  templateUrl: './main.component.html',
  styleUrl: './main.component.css',
})
export class MainComponent {
  // Page State
  isItemPageOpened: boolean = false;
  isLoginButtonClicked: boolean = false;
  isOrderButtonClicked: boolean = false;
  isProfileButtonClicked: boolean = false;
  isCartComponentOpened: boolean = false;

  // Auth State
  isUserSigned: boolean = false;
  loginText: string = 'Üye Ol veya Giriş Yap';

  // Data State
  orderIds: number[] = [];
  isMenuOpen: boolean = false;

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
      next: (ids: number[]) => this.orderIds = ids,
      error: (err) => console.error('Order fetch failed', err),
    });
  }

  private checkAuthStatus() {
    if (this.authService.isLoggedIn()) {
      this.loginText = 'Can'; // Or fetch actual user name
      this.isUserSigned = true;
    } else {
      this.isUserSigned = false;
      this.loginText = 'Üye Ol veya Giriş Yap';
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
    this.loginText = 'Üye Ol veya Giriş Yap';
    this.setItemPageOpened(false);
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
      alert("Hiç siparişiniz yok");
      return;
    }
    this.isOrderButtonClicked = true;
  }

  public closeOrderTrackerComponent() {
    this.isOrderButtonClicked = false;
  }
}
