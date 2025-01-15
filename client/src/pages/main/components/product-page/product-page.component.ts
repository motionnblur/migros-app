import { Component, ElementRef, OnInit, ViewChild } from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { ProductPreviewComponent } from '../product-preview/product-preview.component';
import { CommonModule } from '@angular/common';
import { IProductPreview } from '../../../../interfaces/IProductPreview';
import { categories, data } from '../../../../memory/global-data';
import { EventService } from '../../../../services/event/event.service';
import { ProductBuyComponent } from '../product-buy/product-buy.component';
import { ProductPageSwitcherComponent } from '../product-page-switcher/product-page-switcher.component';

@Component({
  selector: 'app-product-page',
  imports: [
    ProductPreviewComponent,
    CommonModule,
    ProductBuyComponent,
    ProductPageSwitcherComponent,
  ],
  templateUrl: './product-page.component.html',
  styleUrl: './product-page.component.css',
})
export class ProductPageComponent {
  items: IProductPreview[] = [];
  hasProductPreviewOpened: boolean = true;
  hasProductBuyViewOpened: boolean = false;
  selectedProductId!: number;
  categoryName: string = 'Test';

  private onProductPreviewClickedCallback: (productId: number) => void;

  @ViewChild('product_image') productImageRef!: ElementRef<HTMLImageElement>;
  @ViewChild('product_page_ref') productPageRef!: ElementRef<HTMLDivElement>;

  constructor(
    public restService: RestService,
    private eventService: EventService
  ) {
    this.onProductPreviewClickedCallback = this.openProductBuyView.bind(this);
    this.categoryName = categories[data.currentSelectedCategoryId - 1].name;
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

  private openProductBuyView(productId: number) {
    this.productPageRef.nativeElement.style.border = 'none';
    this.selectedProductId = productId;
    this.hasProductBuyViewOpened = true;
    this.hasProductPreviewOpened = false;
  }

  public changePage(pageNumber: number) {
    this.restService
      .getProductPageData(data.currentSelectedCategoryId, pageNumber - 1, 10)
      .subscribe((data: any) => {
        this.items = [];
        data.forEach((productData: IProductPreview) => {
          this.items.push(productData);
        });
      });
  }
  public changePageToFirst() {
    this.restService
      .getProductPageData(data.currentSelectedCategoryId, 0, 10)
      .subscribe((data: any) => {
        this.items = [];
        data.forEach((productData: IProductPreview) => {
          this.items.push(productData);
        });
      });
  }
  public changePageToLast(pageCount: number) {
    this.restService
      .getProductPageData(data.currentSelectedCategoryId, pageCount - 1, 10)
      .subscribe((data: any) => {
        this.items = [];
        data.forEach((productData: IProductPreview) => {
          this.items.push(productData);
        });
      });
  }
}
