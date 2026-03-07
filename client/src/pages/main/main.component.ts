import { Component, HostListener, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink, RouterOutlet } from '@angular/router';
import { Subscription } from 'rxjs';

import { AuthService } from '../../services/auth/auth.service';

@Component({
  selector: 'app-main',
  standalone: true,
  imports: [CommonModule, RouterOutlet, RouterLink],
  templateUrl: './main.component.html',
  styleUrl: './main.component.css',
})
export class MainComponent implements OnInit, OnDestroy {
  isUserSigned = false;
  loginText = 'Uye Ol veya Giris Yap';

  isMenuOpen = false;

  private authStatusSub: Subscription | null = null;

  constructor(
    private authService: AuthService,
    private router: Router,
    private route: ActivatedRoute
  ) {}

  ngOnInit(): void {
    this.authStatusSub = this.authService.userLoggedIn$.subscribe(() => {
      this.checkAuthStatus();
    });

    this.authService.refreshUserSession().subscribe();
  }

  ngOnDestroy(): void {
    this.authStatusSub?.unsubscribe();
  }

  private checkAuthStatus() {
    if (this.authService.isLoggedIn()) {
      const userMail = this.authService.getUserMail();
      this.loginText = userMail || 'Can';
      this.isUserSigned = true;
    } else {
      this.isUserSigned = false;
      this.loginText = 'Uye Ol veya Giris Yap';
    }
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

  public openLoginComponent() {
    this.openModal('login');
  }

  public logoutUser() {
    this.authService.logout();
    this.isMenuOpen = false;
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
    this.isMenuOpen = false;
  }

  public openProfileComponent() {
    if (!this.isUserSigned) {
      this.openLoginComponent();
      return;
    }

    this.openModal('profile');
    this.isMenuOpen = false;
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
    this.isMenuOpen = false;
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
}
