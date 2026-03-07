import { Component, Input, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { RestService } from '../../../../services/rest/rest.service';
import { AuthService } from '../../../../services/auth/auth.service';

@Component({
  selector: 'app-product-preview',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './product-preview.component.html',
  styleUrl: './product-preview.component.css',
})
export class ProductPreviewComponent implements OnInit {
  @Input() productId!: number;
  @Input() productName!: string;
  @Input() productPrice!: number;
  @Input() categoryId!: number;

  imageUrl: string | null = null;

  constructor(
    private restService: RestService,
    private authService: AuthService,
    private router: Router,
    private route: ActivatedRoute
  ) {}

  ngOnInit() {
    if (this.productId) {
      this.restService.getProductImage(this.productId).subscribe({
        next: (blob: Blob) => {
          this.imageUrl = window.URL.createObjectURL(blob);
        },
        error: (err) => console.error('Image load failed:', err),
      });
    }
  }

  onProductViewClicked() {
    this.router.navigate(['/category', this.categoryId, 'product', this.productId]);
  }

  public addProductToUserCart() {
    if (!this.authService.isLoggedIn()) {
      this.router.navigate([{ outlets: { modal: ['login'] } }], {
        relativeTo: this.route,
      });
      return;
    }

    this.restService.addProductToUserCart(this.productId).subscribe({
      next: () => {},
      error: (error) => console.error('Error adding to cart', error),
      complete: () => {
        alert(`${this.productName} sepete eklendi.`);
      },
    });
  }
}
