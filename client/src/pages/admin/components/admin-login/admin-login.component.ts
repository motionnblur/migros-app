import { Component } from '@angular/core';
import { AdminService } from '../../services/admin.service';
import { FormsModule } from '@angular/forms';
import { SignService } from '../../services/sign/sign.service';
import { AuthService } from '../../../../services/auth/auth.service';

@Component({
  selector: 'app-admin-login',
  imports: [FormsModule],
  templateUrl: './admin-login.component.html',
  styleUrl: './admin-login.component.css',
})
export class AdminLoginComponent {
  passwordVisible: boolean = false;
  adminName: string = '';
  adminPassword: string = '';
  constructor(
    private adminService: AdminService,
    private authService: AuthService,
    private signService: SignService
  ) {}
  showPassword() {
    this.passwordVisible = !this.passwordVisible;
  }
  setLoginPhase() {
    this.adminService.setLoginPhase(true);
  }
  //  login(username: string, password: string) {
  //    this.signService.adminLogin(username, password).subscribe(() => {
  //      this.adminService.setLoginCompleted(true);
  //   });
  login(username: string, password: string) {
    this.signService.adminLogin(username, password).subscribe({
      next: (response) => {
        console.log('Admin logged in successfully');
        this.authService.setAdminToken(response!);
        this.adminService.setLoginCompleted(true);
      },
      error: (error) => {
        console.error('Error logging in user');
      },
    });
  }
}
