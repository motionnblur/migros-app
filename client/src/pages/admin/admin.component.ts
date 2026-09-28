import { Component, OnDestroy, OnInit } from '@angular/core';
import { AdminLoginComponent } from './components/admin-login/admin-login.component';
import { CommonModule } from '@angular/common';
import { AdminService } from './services/admin.service';
import { AuthService } from '../../services/auth/auth.service';
import { RouterOutlet } from '@angular/router';
import { Subject, Subscription, takeUntil } from 'rxjs';

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
  private sessionPollingIntervalId: ReturnType<typeof setInterval> | null = null;
  private loginStatusSub: Subscription | null = null;
  private readonly destroy$ = new Subject<void>();

  constructor(private adminService: AdminService, private authService: AuthService) {}

  ngOnInit(): void {
    this.loginStatusSub = this.authService.adminLoggedIn$.pipe(takeUntil(this.destroy$)).subscribe(
      (isLoggedIn) => {
        this.isLoginCompleted = isLoggedIn;
        if (isLoggedIn) {
          this.isLoginPhaseActive = false;
        }
      }
    );

    this.authService.refreshAdminSession().pipe(takeUntil(this.destroy$)).subscribe();
    this.startSessionPolling();

    this.adminService.getLoginPhaseStatus().pipe(takeUntil(this.destroy$)).subscribe((status) => {
      this.isLoginPhaseActive = status;
    });

    this.adminService.getLoginCompletedStatus().pipe(takeUntil(this.destroy$)).subscribe((status) => {
      if (status) {
        this.isLoginCompleted = true;
        this.isLoginPhaseActive = false;
      }
    });
  }

  ngOnDestroy(): void {
    this.stopSessionPolling();
    this.loginStatusSub?.unsubscribe();
    this.destroy$.next();
    this.destroy$.complete();
  }

  setLoginPhase(): void {
    this.adminService.setLoginPhase(true);
  }

  private startSessionPolling() {
    this.stopSessionPolling();
    this.sessionPollingIntervalId = setInterval(() => {
      this.authService.refreshAdminSession().pipe(takeUntil(this.destroy$)).subscribe();
    }, 15000);
  }

  private stopSessionPolling() {
    if (this.sessionPollingIntervalId) {
      clearInterval(this.sessionPollingIntervalId);
      this.sessionPollingIntervalId = null;
    }
  }
}
