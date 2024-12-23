import { Component, Input, OnInit } from '@angular/core';
import { RestService } from '../../services/rest/rest.service';
import { ItemPreviewComponent } from '../item-preview/item-preview.component';
import { NgFor } from '@angular/common';
import { IItemPreview } from '../../interfaces/IItemPreview';

@Component({
  selector: 'app-item-page',
  imports: [ItemPreviewComponent, NgFor],
  templateUrl: './item-page.component.html',
  styleUrl: './item-page.component.css',
})
export class ItemPageComponent implements OnInit {
  items: IItemPreview[] = [];

  constructor(public restService: RestService) {}

  ngOnInit(): void {
    this.restService.getItemPageData(18, 0, 10).subscribe((data: any) => {
      data.forEach((item: any) => {
        const itemData: IItemPreview = {
          itemImageName: item.itemImageName,
          itemTitle: item.itemTitle,
          itemPrice: item.itemPrice,
        };
        this.items.push(itemData);
      });
    });
  }
}
