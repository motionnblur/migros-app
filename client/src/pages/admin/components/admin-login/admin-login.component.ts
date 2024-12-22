import { Component } from '@angular/core';
import { AdminService } from '../../services/admin.service';
import { FormsModule } from '@angular/forms';
import { SignService } from '../../services/sign/sign.service';

@Component({
  selector: 'app-admin-login',
  imports: [FormsModule],
  templateUrl: './admin-login.component.html',
  styleUrl: './admin-login.component.css',
})
export class AdminLoginComponent {
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
    this.adminService.setLoginPhase(true);
  }
  login(username: string, password: string) {
    this.signService.adminLogin(username, password).subscribe(() => {
      this.adminService.setLoginCompleted(true);
    });
  }
}
