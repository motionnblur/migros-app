import { Component, EventEmitter, Output } from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { MatSelectModule } from '@angular/material/select';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatButtonModule } from '@angular/material/button';
import { FormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-sign-user',
  imports: [
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    FormsModule,
    CommonModule,
    MatIconModule,
  ],
  templateUrl: './sign-user.component.html',
  styleUrl: './sign-user.component.css',
})
export class SignUserComponent {
  isSignPhaseActive: boolean = true;
  userMail!: string;
  userPassword!: string;
  userPasswordConfirm!: string;
  passwordVisible = false;

  constructor(private restService: RestService) {}

  @Output() closeComponentEvent = new EventEmitter<void>();
  public openSign() {
    this.isSignPhaseActive = false;
    this.clearFields();
  }
  public openLogin() {
    this.isSignPhaseActive = true;
    this.clearFields();
  }
  private clearFields() {
    this.userMail = '';
    this.userPassword = '';
    this.userPasswordConfirm = '';
  }
  public closeComponent(event: any) {
    if (event.target.id === 'login-component') {
      this.closeComponentEvent.emit();
    }
  }

  public signUser() {
    if (!this.userMail || !this.userPassword || !this.userPasswordConfirm) {
      alert('Please fill in all fields correctly');
      return;
    }
    if (!this.userMail.endsWith('@gmail.com')) {
      alert('Please use a Gmail address');
      return;
    }
    if (this.userPassword !== this.userPasswordConfirm) {
      alert('Passwords do not match');
      return;
    }

    this.restService
      .signUser({
        userMail: this.userMail,
        userPassword: this.userPassword,
      })
      .subscribe({
        next: (response) => {
          console.log('User signed up successfully:', response);
          alert(
            'Confirmation mail has been sent to your mailbox. Please click the link to activate your account in 5 minutes.'
          );
        },
        error: (error) => {
          console.error('Error signing up user:', error);
        },
      });

    this.clearFields();
  }
  public loginUser() {
    if (!this.userMail || !this.userPassword) {
      alert('Please fill in all fields correctly');
      return;
    }
    if (!this.userMail.endsWith('@gmail.com')) {
      alert('Please use a Gmail address');
      return;
    }

    this.restService
      .loginUser({
        userMail: this.userMail,
        userPassword: this.userPassword,
      })
      .subscribe({
        next: (response) => {
          console.log('User logged in successfully:', response);
        },
        error: (error) => {
          console.error('Error logging in user:', error);
        },
      });

    this.clearFields();
  }
}
