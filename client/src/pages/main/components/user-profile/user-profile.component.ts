import {Component, EventEmitter, HostListener, Output} from '@angular/core';
import {FormsModule} from '@angular/forms'; // Only need FormsModule and CommonModule
import {CommonModule} from '@angular/common';
import {RestService} from '../../../../services/rest/rest.service';
import {IUserProfileTable} from '../../../../interfaces/IUserProfileTable';

@Component({
  selector: 'app-user-profile',
  standalone: true, // Assuming you are using standalone components
  imports: [FormsModule, CommonModule], // Removed Mat-related imports
  templateUrl: './user-profile.component.html',
  styleUrl: './user-profile.component.css',
})
export class UserProfileComponent {
  @Output() closeComponentEvent = new EventEmitter<void>();

  @HostListener('document:keydown.escape', ['$event'])
  onKeydownHandler(event: KeyboardEvent) {
    this.closeProfileComponent();
  }

  public userFirstName = '';
  public userLastName = '';
  public userAddress = '';
  public userAddress2 = '';
  public userTown = '';
  public userCountry = '';
  public userPostalCode = '';
  private baseTableData: IUserProfileTable | null = null;

  constructor(private restService: RestService) {
    this.restService.getUserProfileTableData().subscribe({
      next: (data: IUserProfileTable) => {
        if (data) {
          this.userFirstName = data.userFirstName || '';
          this.userLastName = data.userLastName || '';
          this.userAddress = data.userAddress || '';
          this.userAddress2 = data.userAddress2 || '';
          this.userTown = data.userTown || '';
          this.userCountry = data.userCountry || '';
          this.userPostalCode = data.userPostalCode || '';
          this.baseTableData = {...data};
        }
      },
    });
  }

  public closeProfileComponent() {
    this.closeComponentEvent.emit();
  }

  public uploadTableData() {
    const table: IUserProfileTable = {
      userFirstName: this.userFirstName,
      userLastName: this.userLastName,
      userAddress: this.userAddress,
      userAddress2: this.userAddress2,
      userTown: this.userTown,
      userCountry: this.userCountry,
      userPostalCode: this.userPostalCode,
    };

    // Prevent redundant API calls
    if (this.baseTableData && JSON.stringify(this.baseTableData) === JSON.stringify(table)) {
      this.closeProfileComponent();
      return;
    }

    this.restService.uploadUserProfileTableData(table).subscribe({
      next: () => {
        this.baseTableData = {...table};
        alert('Profil başarıyla güncellendi.');
        this.closeProfileComponent();
      },
      error: (err) => console.error('Error:', err)
    });
  }
}
