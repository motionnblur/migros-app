import {Component, OnInit} from '@angular/core';
import {AdminLoginComponent} from './components/admin-login/admin-login.component';
import {CommonModule} from '@angular/common';
import {AdminService} from './services/admin.service';
import {AdminPanelComponent} from './components/admin-panel/admin-panel.component';
import {AuthService} from '../../services/auth/auth.service';

@Component({
  selector: 'app-admin',
  standalone: true, // Ensuring standalone compatibility
  imports: [AdminLoginComponent, CommonModule, AdminPanelComponent],
  templateUrl: './admin.component.html',
  styleUrl: './admin.component.css',
})
export class AdminComponent implements OnInit {
  isLoginPhaseActive: boolean = false;
  isLoginCompleted: boolean = false;

  constructor(private adminService: AdminService, private authService: AuthService) {
  }

  ngOnInit(): void {
    const isAdminLoggedIn = this.authService.isAdminLoggedIn();
    if (isAdminLoggedIn) {
      this.isLoginCompleted = true;
      this.isLoginPhaseActive = false;
    }

    // Subscribe to login phase (e.g., while checking credentials)
    this.adminService.getLoginPhaseStatus().subscribe((status) => {
      this.isLoginPhaseActive = status;
    });

    // Subscribe to successful login
    this.adminService.getLoginCompletedStatus().subscribe((status) => {
      if (status) {
        this.isLoginCompleted = true;
        this.isLoginPhaseActive = false;
      }
    });
  }

  setLoginPhase(): void {
    this.adminService.setLoginPhase(true);
  }
}

