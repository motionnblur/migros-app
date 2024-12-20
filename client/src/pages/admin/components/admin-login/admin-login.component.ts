import { Component } from '@angular/core';

@Component({
  selector: 'app-admin-login',
  imports: [],
  templateUrl: './admin-login.component.html',
  styleUrl: './admin-login.component.css',
})
export class AdminLoginComponent {
  passwordVisible: boolean = false;
  showPassword() {
    this.passwordVisible = !this.passwordVisible;
  }
}
