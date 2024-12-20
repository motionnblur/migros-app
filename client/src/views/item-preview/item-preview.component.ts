import { Component, Input, input } from '@angular/core';

@Component({
  selector: 'app-item-preview',
  imports: [],
  templateUrl: './item-preview.component.html',
  styleUrl: './item-preview.component.css',
})
export class ItemPreviewComponent {
  @Input() itemTitle!: string;
  @Input() itemPrice!: number;

  getItemTitle() {
    return this.itemTitle;
  }
  getItemPrice() {
    return this.itemPrice;
  }
}
