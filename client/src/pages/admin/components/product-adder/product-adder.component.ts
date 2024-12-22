import { Component, Input } from '@angular/core';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'app-product-adder',
  imports: [FormsModule],
  templateUrl: './product-adder.component.html',
  styleUrl: './product-adder.component.css',
})
export class ProductAdderComponent {
  @Input() productName!: string;
  @Input() price!: number;
  @Input() count!: number;
  @Input() discount!: number;
  @Input() description!: string;
}
