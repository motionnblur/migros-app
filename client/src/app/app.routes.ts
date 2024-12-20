import { Routes } from '@angular/router';
import { MainComponent } from '../pages/main/main.component';
import { AdminComponent } from '../pages/admin/admin.component';

export const routes: Routes = [
  {
    path: '',
    component: MainComponent,
  },
  { path: 'admin', component: AdminComponent },
];
