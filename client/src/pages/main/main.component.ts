import { Component } from '@angular/core';
import { EventService } from '../../services/event/event.service';
import { ProductPageComponent } from './components/product-page/product-page.component';
import { CommonModule } from '@angular/common';
import { DiscoverComponent } from './components/discover-area/parent/discover-area.component';
import { data } from '../../memory/global-data';
import { LoginComponent } from './components/login/login.component';
import { MatSelectModule } from '@angular/material/select';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatButtonModule } from '@angular/material/button';
import { RestService } from '../../services/rest/rest.service';
import { FormsModule } from '@angular/forms';
import { SignUserComponent } from './components/sign-user/sign-user.component';
import { AuthService } from '../../services/auth/auth.service';
import { MatMenuModule } from '@angular/material/menu';
import { UserCartComponent } from './components/user-cart/user-cart.component';
import { PaymentComponent } from './components/payment/payment.component';

@Component({
  selector: 'app-main',
  imports: [
    DiscoverComponent,
    ProductPageComponent,
    CommonModule,
    LoginComponent,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    FormsModule,
    SignUserComponent,
    MatMenuModule,
    UserCartComponent,
  ],
  templateUrl: './main.component.html',
  styleUrl: './main.component.css',
})
export class MainComponent {
  isItemPageOpened: boolean = false;
  isLoginButtonClicked: boolean = false;
  isOrderButtonClicked: boolean = false;
  isUserSigned: boolean = false;
  title: string = 'migros-app';
  userMail!: string;
  userPassword!: string;
  loginText: string = 'Üye Ol veya Giriş Yap';
  isCartComponentOpened: boolean = false;
  isPaymentComponentOpened: boolean = false;

  constructor(
    private eventManager: EventService,
    private restService: RestService,
    private authService: AuthService
  ) {
    eventManager.on('openItemPage', (categoryId: number) => {
      data.currentSelectedCategoryId = categoryId;
      this.setItemPageOpened(true);
    });
    if (this.authService.isLoggedIn()) {
      this.loginText = 'Can';
      this.isUserSigned = true;
    } else {
      if (this.authService.isTokenExists()) {
        this.isUserSigned = false;
        this.authService.logout();
      }
      this.loginText = 'Üye Ol veya Giriş Yap';
    }
  }

  public openLoginComponent() {
    this.isLoginButtonClicked = true;
  }
  public openOrderComponent() {
    if (!this.authService.isLoggedIn()) {
      this.isLoginButtonClicked = true;
      return;
    }
    this.isOrderButtonClicked = true;
  }
  public loginUser() {
    this.loginText = 'Can';
    this.isUserSigned = true;
  }
  public isUserLoggedIn() {
    return this.isUserSigned;
  }

  public closeLoginComponent() {
    this.isLoginButtonClicked = false;
  }
  public hasItemPageOpened(): boolean {
    return this.isItemPageOpened;
  }
  public openCartComponent() {
    this.isCartComponentOpened = true;
  }
  public closeCartComponent() {
    this.isCartComponentOpened = false;
  }

  private setItemPageOpened(value: boolean) {
    this.isItemPageOpened = value;
  }
}
