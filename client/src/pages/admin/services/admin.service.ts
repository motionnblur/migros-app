import { Injectable } from '@angular/core';
import { AdminComponent } from '../admin.component';

@Injectable({
  providedIn: 'root',
})
export class AdminService {
  private adminComponent!: AdminComponent;

  setAdminComponent(adminComponent: AdminComponent) {
    this.adminComponent = adminComponent;
  }

  getAdminComponent(): AdminComponent {
    return this.adminComponent;
  }
}
