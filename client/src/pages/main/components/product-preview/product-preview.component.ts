import {Component, Input, OnInit} from '@angular/core';
import {CommonModule} from '@angular/common'; // Important for [src] and [ngClass]
import {RestService} from '../../../../services/rest/rest.service';
import {EventService} from '../../../../services/event/event.service';
import {AuthService} from '../../../../services/auth/auth.service';

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

  imageUrl: string | null = null;

  constructor(
    private restService: RestService,
    private eventService: EventService,
    private authService: AuthService
  ) {
  }

  ngOnInit() {
    if (this.productId) {
      this.restService.getProductImage(this.productId).subscribe({
        next: (blob: Blob) => {
          this.imageUrl = window.URL.createObjectURL(blob);
        },
        error: (err) => console.error('Image load failed:', err)
      });
    }
  }

  onProductViewClicked() {
    this.eventService.trigger('onProductPreviewClicked', this.productId);
  }

  public addProductToUserCart() {
    if (!this.authService.isLoggedIn()) {
      this.eventService.trigger('openLogin');
      return;
    }

    this.restService.addProductToUserCart(this.productId).subscribe({
      next: () => {
        // You could trigger a 'cartUpdated' event here if you have a cart badge in header
      },
      error: (error) => console.error('Error adding to cart', error),
      complete: () => {
        // Using a simple alert for now, but consider a Bootstrap Toast later!
        alert(`${this.productName} sepete eklendi.`);
      },
    });
  }
}
