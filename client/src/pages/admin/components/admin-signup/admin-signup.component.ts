import { Component } from '@angular/core';
import { AdminService } from '../../services/admin.service';
import { SignService } from '../../services/sign/sign.service';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'app-admin-signup',
  imports: [FormsModule],
  templateUrl: './admin-signup.component.html',
  styleUrl: './admin-signup.component.css',
})
export class AdminSignupComponent {
  passwordVisible: boolean = false;
  adminName = '';
  adminPassword = '';
  constructor(
    private adminService: AdminService,
    private signService: SignService
  ) {}
  showPassword() {
    this.passwordVisible = !this.passwordVisible;
  }
  setLoginPhase() {
    this.adminService.setLoginPhase(false);
  }
  signup(adminName: string, adminPassword: string) {
    this.signService.adminSignup(adminName, adminPassword).subscribe(() => {
      this.setLoginPhase();
    });
  }
}
