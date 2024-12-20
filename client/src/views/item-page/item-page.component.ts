import { Component, Input, OnInit } from '@angular/core';
import { RestService } from '../../services/rest/rest.service';
import { ItemPreviewComponent } from '../item-preview/item-preview.component';
import { NgFor } from '@angular/common';

@Component({
  selector: 'app-item-page',
  imports: [ItemPreviewComponent, NgFor],
  templateUrl: './item-page.component.html',
  styleUrl: './item-page.component.css',
})
export class ItemPageComponent implements OnInit {
  @Input() itemId!: number;
  items = [
    { title: 'item1', price: 100 },
    { title: 'item2', price: 200 },
    { title: 'item3', price: 300 },
    { title: 'item4', price: 400 },
    { title: 'item5', price: 500 },
    { title: 'item6', price: 600 },
    { title: 'item7', price: 700 },
    { title: 'item8', price: 800 },
    { title: 'item9', price: 900 },
    { title: 'item10', price: 1000 },
  ];

  constructor(public restService: RestService) {}

  ngOnInit(): void {
    this.restService.getItemPageData(1, 0, 10).subscribe((data) => {
      console.log(data);
    });
  }
}
