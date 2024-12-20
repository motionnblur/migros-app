import { Component } from '@angular/core';
import { AdminService } from '../../services/admin.service';

@Component({
  selector: 'app-admin-signup',
  imports: [],
  templateUrl: './admin-signup.component.html',
  styleUrl: './admin-signup.component.css',
})
export class AdminSignupComponent {
  passwordVisible: boolean = false;
  constructor(private adminService: AdminService) {}
  showPassword() {
    this.passwordVisible = !this.passwordVisible;
  }
  setLoginPhase() {
    this.adminService.setLoginPhase(false);
  }
}
