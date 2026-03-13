import { Component } from '@angular/core';
import { AdminService } from '../../services/admin.service';
import { FormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { SignService } from '../../services/sign/sign.service';
import { AuthService } from '../../../../services/auth/auth.service';
import { supabaseImageUrl } from '../../../../app/config/supabase-assets';

@Component({
  selector: 'app-admin-login',
  standalone: true,
  imports: [FormsModule, CommonModule],
  templateUrl: './admin-login.component.html',
  styleUrl: './admin-login.component.css',
})
export class AdminLoginComponent {
  readonly supabaseImageUrl = supabaseImageUrl;
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

  login(username: string, password: string) {
    if (!username || !password) return;

    this.signService.adminLogin(username, password).subscribe({
      next: (success) => {
        if (!success) {
          return;
        }

        this.authService.refreshAdminSession().subscribe((loggedIn) => {
          if (loggedIn) {
            this.adminService.setLoginCompleted(true);
          }
        });
      },
      error: (error) => {
        console.error('Login failed', error);
      },
    });
  }
}
