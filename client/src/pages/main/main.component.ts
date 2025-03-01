import { Component } from '@angular/core';
import { EventService } from '../../services/event/event.service';
import { ProductPageComponent } from './components/product-page/product-page.component';
import { CommonModule } from '@angular/common';
import { DiscoverComponent } from './components/discover-area/parent/discover-area.component';
import { data } from '../../memory/global-data';
import { LoginComponent } from './components/login/login.component';
import { MatSelectModule } from '@angular/material/select';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatButtonModule } from '@angular/material/button';

@Component({
  selector: 'app-main',
  imports: [
    DiscoverComponent,
    ProductPageComponent,
    CommonModule,
    LoginComponent,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
  ],
  templateUrl: './main.component.html',
  styleUrl: './main.component.css',
})
export class MainComponent {
  isItemPageOpened: boolean = false;
  isLoginButtonClicked: boolean = false;
  isSignPhaseActive: boolean = true;
  title: string = 'migros-app';

  constructor(private eventManager: EventService) {
    eventManager.on('openItemPage', (categoryId: number) => {
      data.currentSelectedCategoryId = categoryId;
      this.setItemPageOpened(true);
    });
  }

  public openLoginComponent() {
    this.isLoginButtonClicked = true;
  }
  public hasItemPageOpened(): boolean {
    return this.isItemPageOpened;
  }
  public openSign() {
    this.isSignPhaseActive = false;
  }
  public openLogin() {
    this.isSignPhaseActive = true;
  }
  private setItemPageOpened(value: boolean) {
    this.isItemPageOpened = value;
  }
}
