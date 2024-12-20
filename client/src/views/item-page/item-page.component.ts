import { Component, Input, OnInit } from '@angular/core';
import { RestService } from '../../services/rest/rest.service';
import { ItemPreviewComponent } from '../item-preview/item-preview.component';
import { NgFor } from '@angular/common';
import { itemDataI } from '../../interfaces/itemDataI';

@Component({
  selector: 'app-item-page',
  imports: [ItemPreviewComponent, NgFor],
  templateUrl: './item-page.component.html',
  styleUrl: './item-page.component.css',
})
export class ItemPageComponent implements OnInit {
  items: itemDataI[] = [];

  constructor(public restService: RestService) {}

  ngOnInit(): void {
    this.restService.getItemPageData(1, 0, 10).subscribe((data: any) => {
      data.forEach((item: any) => {
        const itemData: itemDataI = {
          title: item.itemName,
          price: item.itemPrice,
        };
        this.items.push(itemData);
      });
    });
  }
}
