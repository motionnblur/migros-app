import { Component } from '@angular/core';
import { CategoryButtonComponent } from '../child/category-button/category-button.component';

@Component({
  selector: 'app-discover',
  imports: [CategoryButtonComponent],
  templateUrl: './discover-area.component.html',
  styleUrl: './discover-area.component.css',
})
export class DiscoverComponent {}
