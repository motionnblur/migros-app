import { Component } from '@angular/core';
import { EventService } from '../../services/event/event.service';
import { ItemPageComponent } from './components/item-page/item-page.component';
import { CommonModule } from '@angular/common';
import { DiscoverComponent } from './components/discover-area/parent/discover-area.component';
import { data } from '../../memory/global-data';

@Component({
  selector: 'app-main',
  imports: [DiscoverComponent, ItemPageComponent, CommonModule],
  templateUrl: './main.component.html',
  styleUrl: './main.component.css',
})
export class MainComponent {
  isItemPageOpened: boolean = false;
  title = 'migros-app';

  constructor(private eventManager: EventService) {
    eventManager.on('openItemPage', (categoryId: number) => {
      data.currentSelectedCategoryId = categoryId;
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
