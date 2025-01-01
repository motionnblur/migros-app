import { Component, ElementRef, Input, ViewChild } from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { IProductData } from '../../../../interfaces/IProductData';
import { CommonModule } from '@angular/common';
import { IProductDescription } from '../../../../interfaces/IProductDescription';

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
  productDescriptions: IProductDescription[] = [];
  currentProductDescriptionBody: string = '';

  constructor(private restService: RestService) {}

  ngOnInit() {
    this.restService
      .getProductData(this.productId)
      .subscribe((data: IProductData) => {
        this.productData = data;
      });
    this.restService
      .getProductDescription(this.productId)
      .subscribe((data: IProductDescription[]) => {
        this.productDescriptions = data;
        this.currentProductDescriptionBody =
          this.productDescriptions[0].descriptionTabContent;
      });
  }
  ngAfterViewInit() {
    this.restService.getProductImage(this.productId).subscribe((data: Blob) => {
      this.productImageRef.nativeElement.src = URL.createObjectURL(data);
    });
  }
  changeTab(index: number) {
    this.currentProductDescriptionBody =
      this.productDescriptions[index].descriptionTabContent;
  }
}
