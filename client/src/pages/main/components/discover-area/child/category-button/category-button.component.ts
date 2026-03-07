import { Component, Input } from '@angular/core';
import { Router } from '@angular/router';

@Component({
  selector: 'app-category-button',
  standalone: true,
  imports: [],
  templateUrl: './category-button.component.html',
  styleUrl: './category-button.component.css',
})
export class CategoryButtonComponent {
  @Input() image: string = 'meyve.png';
  @Input() name: string = 'Name';
  @Input() categoryId!: number;

  constructor(private router: Router) {}

  openItemPage() {
    this.router.navigate(['/category', this.categoryId]);
  }
}
