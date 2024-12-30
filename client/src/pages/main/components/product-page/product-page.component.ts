import { Component, ElementRef, OnInit, ViewChild } from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { ProductPreviewComponent } from '../product-preview/product-preview.component';
import { CommonModule, NgFor } from '@angular/common';
import { IProductPreview } from '../../../../interfaces/IProductPreview';
import { data } from '../../../../memory/global-data';
import { EventService } from '../../../../services/event/event.service';
import { ProductBuyComponent } from '../product-buy/product-buy.component';

@Component({
  selector: 'app-product-page',
  imports: [ProductPreviewComponent, CommonModule, ProductBuyComponent],
  templateUrl: './product-page.component.html',
  styleUrl: './product-page.component.css',
})
export class ProductPageComponent implements OnInit {
  items: IProductPreview[] = [];
  hasProductPreviewOpened: boolean = true;
  hasProductBuyViewOpened: boolean = false;
  selectedProductId!: number;

  private onProductPreviewClickedCallback: (productId: number) => void;

  @ViewChild('product_image') productImageRef!: ElementRef<HTMLImageElement>;
  @ViewChild('product_page_ref') productPageRef!: ElementRef<HTMLDivElement>;

  constructor(
    public restService: RestService,
    private eventService: EventService
  ) {
    this.onProductPreviewClickedCallback = this.openProductBuyView.bind(this);
  }
  ngOnInit(): void {
    this.restService
      .getProductPageData(data.currentSelectedCategoryId, 0, 10)
      .subscribe((data: any) => {
        data.forEach((productData: IProductPreview) => {
          this.items.push(productData);
        });
      });

    this.eventService.on(
      'onProductPreviewClicked',
      this.onProductPreviewClickedCallback
    );
  }
  ngOnDestroy(): void {
    this.eventService.off(
      'onProductPreviewClicked',
      this.onProductPreviewClickedCallback
    );
  }

  openProductBuyView(productId: number) {
    this.productPageRef.nativeElement.style.border = 'none';
    this.selectedProductId = productId;
    this.hasProductBuyViewOpened = true;
    this.hasProductPreviewOpened = false;
  }
}
