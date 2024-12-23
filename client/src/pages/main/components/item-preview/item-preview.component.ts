import { Component, Input, input } from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { data } from '../../../../memory/global-data';

@Component({
  selector: 'app-item-preview',
  imports: [],
  templateUrl: './item-preview.component.html',
  styleUrl: './item-preview.component.css',
})
export class ItemPreviewComponent {
  @Input() itemId!: number;
  @Input() itemImageName!: string;
  @Input() itemTitle!: string;
  @Input() itemPrice!: number;
  imageUrl: string | null = null;

  constructor(private restService: RestService) {}

  getItemTitle() {
    return this.itemTitle;
  }
  getItemPrice() {
    return this.itemPrice;
  }

  ngOnInit() {
    this.restService.getItemImage(this.itemId).subscribe((blob: Blob) => {
      const url = window.URL.createObjectURL(blob); // Create a URL for the blob
      this.imageUrl = url;
    });
  }
}
