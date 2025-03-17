import { Component, Input, input } from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { EventService } from '../../../../services/event/event.service';

@Component({
  selector: 'app-product-preview',
  imports: [],
  templateUrl: './product-preview.component.html',
  styleUrl: './product-preview.component.css',
})
export class ProductPreviewComponent {
  @Input() productId!: number;
  @Input() productName!: string;
  @Input() productTitle!: string;
  @Input() productPrice!: number;
  imageUrl: string | null = null;

  constructor(
    private restService: RestService,
    private eventService: EventService
  ) {}
  ngOnInit() {
    this.restService.getProductImage(this.productId).subscribe((blob: Blob) => {
      const url: string = window.URL.createObjectURL(blob); // Create a URL for the blob
      this.imageUrl = url;
    });
  }

  getproductTitle() {
    return this.productTitle;
  }
  getproductPrice() {
    return this.productPrice;
  }
  onProductViewClicked() {
    this.eventService.trigger('onProductPreviewClicked', this.productId);
  }
  public addProductToUserCart() {
    this.restService.addProductToUserCart(this.productId).subscribe({
      next: () => {
        // // Emit the event
      },
      error: (error) => {
        console.error('Error adding product to user cart');
      },
      complete: () => {
        alert('Product added to cart');
      },
    });
  }
}
