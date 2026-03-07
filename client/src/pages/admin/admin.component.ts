import { Component, OnDestroy, OnInit } from '@angular/core';
import { AdminLoginComponent } from './components/admin-login/admin-login.component';
import { CommonModule } from '@angular/common';
import { AdminService } from './services/admin.service';
import { AuthService } from '../../services/auth/auth.service';
import { RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [AdminLoginComponent, CommonModule, RouterOutlet],
  templateUrl: './admin.component.html',
  styleUrl: './admin.component.css',
})
export class AdminComponent implements OnInit, OnDestroy {
  isLoginPhaseActive: boolean = false;
  isLoginCompleted: boolean = false;
  private tokenExpiryIntervalId: ReturnType<typeof setInterval> | null = null;

  constructor(private adminService: AdminService, private authService: AuthService) {}

  ngOnInit(): void {
    const isAdminLoggedIn = this.authService.isAdminLoggedIn();
    if (isAdminLoggedIn) {
      this.isLoginCompleted = true;
      this.isLoginPhaseActive = false;
    }

    this.startTokenExpiryPolling();

    this.adminService.getLoginPhaseStatus().subscribe((status) => {
      this.isLoginPhaseActive = status;
    });

    this.adminService.getLoginCompletedStatus().subscribe((status) => {
      if (status) {
        this.isLoginCompleted = true;
        this.isLoginPhaseActive = false;
      }
    });
  }

  ngOnDestroy(): void {
    this.stopTokenExpiryPolling();
  }

  setLoginPhase(): void {
    this.adminService.setLoginPhase(true);
  }

  private startTokenExpiryPolling() {
    this.stopTokenExpiryPolling();
    this.tokenExpiryIntervalId = setInterval(() => {
      const token = this.authService.getAdminToken();
      if (!token) {
        return;
      }
      if (this.authService.isTokenExpired(token)) {
        this.authService.logoutAdmin();
        this.isLoginCompleted = false;
        this.isLoginPhaseActive = false;
      }
    }, 15000);
  }

  private stopTokenExpiryPolling() {
    if (this.tokenExpiryIntervalId) {
      clearInterval(this.tokenExpiryIntervalId);
      this.tokenExpiryIntervalId = null;
    }
  }
}
