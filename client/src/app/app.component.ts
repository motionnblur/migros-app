import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { DiscoverComponent } from '../views/discover-area/parent/discover-area.component';
import { ItemPageComponent } from '../views/item-page/item-page.component';
import { CommonModule } from '@angular/common';
import { EventService } from '../services/event/event.service';
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, DiscoverComponent, ItemPageComponent, CommonModule],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent {
  isItemPageOpened: boolean = false;
  title = 'migros-app';

  constructor(private eventManager: EventService) {
    eventManager.on('openItemPage', (categoryId: number) => {
      this.setItemPageOpened(true);
    });
  }

  hasItemPageOpened(): boolean {
    return this.isItemPageOpened;
  }
  setItemPageOpened(value: boolean) {
    this.isItemPageOpened = value;
  }
}
