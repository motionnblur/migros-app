import { Component, Input, OnInit } from '@angular/core';
import { RestService } from '../../services/rest/rest.service';
import { ItemPreviewComponent } from '../item-preview/item-preview.component';

@Component({
  selector: 'app-item-page',
  imports: [ItemPreviewComponent],
  templateUrl: './item-page.component.html',
  styleUrl: './item-page.component.css',
})
export class ItemPageComponent implements OnInit {
  constructor(public restService: RestService) {}
  @Input() itemId!: number;

  ngOnInit(): void {
    this.restService.getItemPageData(1, 0, 10).subscribe((data) => {
      console.log(data);
    });
  }
}
