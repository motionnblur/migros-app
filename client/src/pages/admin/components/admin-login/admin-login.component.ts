import {Component} from '@angular/core';
import {AdminService} from '../../services/admin.service';
import {FormsModule} from '@angular/forms';
import {CommonModule} from '@angular/common'; // Required for ngClass
import {SignService} from '../../services/sign/sign.service';
import {AuthService} from '../../../../services/auth/auth.service';

@Component({
  selector: 'app-admin-login',
  standalone: true,
  imports: [FormsModule, CommonModule],
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
  ) {
  }

  showPassword() {
    this.passwordVisible = !this.passwordVisible;
  }

  login(username: string, password: string) {
    if (!username || !password) return;

    this.signService.adminLogin(username, password).subscribe({
      next: (response) => {
        if (response) {
          this.authService.setAdminToken(response);
          this.adminService.setLoginCompleted(true);
        }
      },
      error: (error) => {
        console.error('Login failed', error);
        // Hint: You could add a 'showErrorMessage' variable here
        // to show a Bootstrap Alert if the credentials are wrong
      }
    });
  }
}
