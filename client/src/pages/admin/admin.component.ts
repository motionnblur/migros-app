import { Component } from '@angular/core';
import { AdminLoginComponent } from './components/admin-login/admin-login.component';

@Component({
  selector: 'app-admin',
  imports: [AdminLoginComponent],
  templateUrl: './admin.component.html',
  styleUrl: './admin.component.css',
})
export class AdminComponent {}
