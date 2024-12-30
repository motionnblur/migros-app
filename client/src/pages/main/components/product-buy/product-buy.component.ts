import { Component, ElementRef, Input, ViewChild } from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { IProductData } from '../../../../interfaces/IProductData';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-product-buy',
  imports: [CommonModule],
  templateUrl: './product-buy.component.html',
  styleUrl: './product-buy.component.css',
})
export class ProductBuyComponent {
  @Input() productId!: number;
  @ViewChild('product_image_ref')
  productImageRef!: ElementRef<HTMLImageElement>;
  productData!: IProductData;

  constructor(private restService: RestService) {}

  ngOnInit() {
    this.restService
      .getProductData(this.productId)
      .subscribe((data: IProductData) => {
        this.productData = data;
      });
  }

  ngAfterViewInit() {
    this.restService.getProductImage(this.productId).subscribe((data: Blob) => {
      this.productImageRef.nativeElement.src = URL.createObjectURL(data);
    });
  }
}
