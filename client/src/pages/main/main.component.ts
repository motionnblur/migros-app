import { Component, HostListener, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
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

  private routeSub: Subscription | null = null;

  constructor(
    private authService: AuthService,
    private router: Router,
    private route: ActivatedRoute
  ) {}

  ngOnInit(): void {
    this.checkAuthStatus();
    this.routeSub = this.router.events.subscribe((event) => {
      if (event instanceof NavigationEnd) {
        this.checkAuthStatus();
      }
    });
  }

  ngOnDestroy(): void {
    this.routeSub?.unsubscribe();
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
    this.checkAuthStatus();
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
