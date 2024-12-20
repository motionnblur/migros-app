import { Component, Input, OnInit } from '@angular/core';
import { RestService } from '../../services/rest/rest.service';

@Component({
  selector: 'app-item-page',
  imports: [],
  templateUrl: './item-page.component.html',
  styleUrl: './item-page.component.css',
})
export class ItemPageComponent implements OnInit {
  constructor(public restService: RestService) {}
  @Input() itemId!: number;

  ngOnInit(): void {
    this.restService.getItemPageData().subscribe((data) => {
      console.log('data');
    });
  }
}
