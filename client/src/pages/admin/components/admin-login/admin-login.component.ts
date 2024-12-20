import { Component } from '@angular/core';
import { AdminService } from '../../services/admin.service';

@Component({
  selector: 'app-admin-login',
  imports: [],
  templateUrl: './admin-login.component.html',
  styleUrl: './admin-login.component.css',
})
export class AdminLoginComponent {
  passwordVisible: boolean = false;
  constructor(private adminService: AdminService) {}
  showPassword() {
    this.passwordVisible = !this.passwordVisible;
  }
  setLoginPhase() {
    this.adminService.setLoginPhase(true);
  }
}
