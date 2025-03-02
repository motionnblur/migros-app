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
import { RestService } from '../../services/rest/rest.service';
import { FormsModule } from '@angular/forms';
import { SignUserComponent } from './components/sign-user/sign-user.component';

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
    FormsModule,
    SignUserComponent,
  ],
  templateUrl: './main.component.html',
  styleUrl: './main.component.css',
})
export class MainComponent {
  isItemPageOpened: boolean = false;
  isLoginButtonClicked: boolean = false;
  title: string = 'migros-app';
  userMail!: string;
  userPassword!: string;

  constructor(
    private eventManager: EventService,
    private restService: RestService
  ) {
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

  private setItemPageOpened(value: boolean) {
    this.isItemPageOpened = value;
  }
}
