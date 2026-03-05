import {Component, OnInit} from '@angular/core';
import {AdminLoginComponent} from './components/admin-login/admin-login.component';
import {CommonModule} from '@angular/common';
import {AdminService} from './services/admin.service';
import {AdminPanelComponent} from './components/admin-panel/admin-panel.component';

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

  constructor(private adminService: AdminService) {
  }

  ngOnInit(): void {
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
