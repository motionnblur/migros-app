import { Component, Input } from '@angular/core';
import { EventService } from '../../../../../../services/event/event.service';

@Component({
  selector: 'app-category-button',
  imports: [],
  templateUrl: './category-button.component.html',
  styleUrl: './category-button.component.css',
})
export class CategoryButtonComponent {
  @Input() image: string = 'meyve.png';
  @Input() name: string = 'Name';
  @Input() categoryId!: number;

  constructor(private eventManager: EventService) {}

  openItemPage() {
    this.eventManager.trigger('openItemPage', this.categoryId);
  }
}
