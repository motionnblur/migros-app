import { Component, ElementRef, Input, ViewChild } from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';

@Component({
  selector: 'app-product-buy',
  imports: [],
  templateUrl: './product-buy.component.html',
  styleUrl: './product-buy.component.css',
})
export class ProductBuyComponent {
  @Input() productId!: number;
  @ViewChild('product_image_ref')
  productImageRef!: ElementRef<HTMLImageElement>;

  constructor(private restService: RestService) {}

  ngOnInit() {
    this.restService.getProductImage(this.productId).subscribe((data: Blob) => {
      this.productImageRef.nativeElement.src = URL.createObjectURL(data);
    });
  }
}
