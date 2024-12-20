import { Component } from '@angular/core';
import { AdminLoginComponent } from './components/admin-login/admin-login.component';
import { CommonModule } from '@angular/common';
import { AdminSignupComponent } from './components/admin-signup/admin-signup.component';
import { AdminService } from './services/admin.service';

@Component({
  selector: 'app-admin',
  imports: [
    AdminLoginComponent,
    AdminSignupComponent,
    CommonModule,
    AdminSignupComponent,
  ],
  templateUrl: './admin.component.html',
  styleUrl: './admin.component.css',
})
export class AdminComponent {
  isLoginPhaseActive = false;

  constructor(private adminService: AdminService) {}

  ngOnInit(): void {
    this.adminService.getLoginPhaseStatus().subscribe((status) => {
      this.isLoginPhaseActive = status;
    });
  }

  setLoginPhase(): void {
    this.adminService.setLoginPhase(true);
  }
}
