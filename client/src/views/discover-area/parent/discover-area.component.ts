import { Component, OnInit } from '@angular/core';
import { CategoryButtonComponent } from '../child/category-button/category-button.component';
import { RestService } from '../../../services/rest/rest.service';

@Component({
  selector: 'app-discover',
  imports: [CategoryButtonComponent],
  templateUrl: './discover-area.component.html',
  styleUrl: './discover-area.component.css',
})
export class DiscoverComponent implements OnInit {
  constructor(public restService: RestService) {}

  ngOnInit(): void {
    this.restService.getHello().subscribe((data) => {
      console.log(data);
    });
  }
}
