import { Component, EventEmitter, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { RestService } from '../../../../services/rest/rest.service';
import { IUserProfileTable } from '../../../../interfaces/IUserProfileTable';

@Component({
  selector: 'app-user-profile',
  imports: [FormsModule, MatFormFieldModule, MatInputModule, MatButtonModule],
  templateUrl: './user-profile.component.html',
  styleUrl: './user-profile.component.css',
})
export class UserProfileComponent {
  @Output() closeComponentEvent = new EventEmitter<void>();
  public userFirstName: string = '';
  public userLastName: string = '';
  public userAddress: string = '';
  public userAddress2: string = '';
  public userTown: string = '';
  public userCountry: string = '';
  public userPostalCode: string = '';
  private baseTableData: IUserProfileTable | null = null;

  constructor(private restService: RestService) {
    restService.getUserProfileTableData().subscribe({
      next: (data: IUserProfileTable) => {
        this.userFirstName = data.userFirstName;
        this.userLastName = data.userLastName;
        this.userAddress = data.userAddress;
        this.userAddress2 = data.userAddress2;
        this.userTown = data.userTown;
        this.userCountry = data.userCountry;
        this.userPostalCode = data.userPostalCode;

        this.baseTableData = data;
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

    if (
      this.baseTableData &&
      JSON.stringify(this.baseTableData) === JSON.stringify(table)
    ) {
      return;
    }

    this.restService.uploadUserProfileTableData(table).subscribe({
      next: () => {
        console.log('Table data uploaded successfully');
        this.baseTableData = table;
      },
      error: (error) => {
        console.error('Error uploading table data');
      },
      complete: () => {
        alert('Kaydedildi');
      },
    });
  }
}
