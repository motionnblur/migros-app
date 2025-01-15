import { Component, ElementRef, OnInit, ViewChild } from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { ProductPreviewComponent } from '../product-preview/product-preview.component';
import { CommonModule } from '@angular/common';
import { IProductPreview } from '../../../../interfaces/IProductPreview';
import { categories, data } from '../../../../memory/global-data';
import { EventService } from '../../../../services/event/event.service';
import { ProductBuyComponent } from '../product-buy/product-buy.component';
import { ProductPageSwitcherComponent } from '../product-page-switcher/product-page-switcher.component';
import { ISubCategory } from '../../../../interfaces/ISubCategory';

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
  subCategories: ISubCategory[] = [];
  totalProductCount: number = 0;

  private onProductPreviewClickedCallback: (productId: number) => void;

  @ViewChild('product_image') productImageRef!: ElementRef<HTMLImageElement>;
  @ViewChild('product_page_ref') productPageRef!: ElementRef<HTMLDivElement>;

  constructor(
    public restService: RestService,
    private eventService: EventService
  ) {
    this.onProductPreviewClickedCallback = this.openProductBuyView.bind(this);
    this.categoryName = categories[data.currentSelectedCategoryId - 1].name;

    this.subCategories = [
      { subCategoryId: 1, subCategoryName: 'Meyve', productCount: 0 },
      { subCategoryId: 2, subCategoryName: 'Sebze', productCount: 0 },
      { subCategoryId: 3, subCategoryName: 'Tohum', productCount: 0 },
    ];
  }
  ngOnInit(): void {
    this.restService
      .getProductPageData(data.currentSelectedCategoryId, 0, 10)
      .subscribe((data: any) => {
        data.forEach((productData: IProductPreview) => {
          this.items.push(productData);
        });
      });

    this.restService
      .getSubCategories(data.currentSelectedCategoryId)
      .subscribe({
        next: (data: any) => {
          this.subCategories = data;
          console.log(data);
        },
        error: (error: any) => {
          console.error(error);
        },
        complete: () => {
          console.log('completed');
        },
      });

    this.restService
      .getProductCountsFromCategory(data.currentSelectedCategoryId)
      .subscribe({
        next: (data: any) => {
          this.totalProductCount = data;
        },
        error: (error: any) => {
          console.error(error);
        },
        complete: () => {
          console.log('completed');
        },
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
    if (data.currentSelectedSubCategoryName !== '') {
      this.restService
        .getProducstFromSubCategory(
          data.currentSelectedSubCategoryName,
          pageNumber - 1,
          10
        )
        .subscribe((data: any) => {
          this.items = [];
          data.forEach((productData: IProductPreview) => {
            this.items.push(productData);
          });
        });
    } else {
      this.restService
        .getProductPageData(data.currentSelectedCategoryId, pageNumber - 1, 10)
        .subscribe((data: any) => {
          this.items = [];
          data.forEach((productData: IProductPreview) => {
            this.items.push(productData);
          });
        });
    }
  }
  public changePageToFirst() {
    if (data.currentSelectedSubCategoryName !== '') {
      this.restService
        .getProducstFromSubCategory(data.currentSelectedSubCategoryName, 0, 10)
        .subscribe((data: any) => {
          this.items = [];
          data.forEach((productData: IProductPreview) => {
            this.items.push(productData);
          });
        });
    } else {
      this.restService
        .getProductPageData(data.currentSelectedCategoryId, 0, 10)
        .subscribe((data: any) => {
          this.items = [];
          data.forEach((productData: IProductPreview) => {
            this.items.push(productData);
          });
        });
    }
  }
  public changePageToLast(pageCount: number) {
    if (data.currentSelectedSubCategoryName !== '') {
      this.restService
        .getProducstFromSubCategory(
          data.currentSelectedSubCategoryName,
          pageCount - 1,
          10
        )
        .subscribe((data: any) => {
          this.items = [];
          data.forEach((productData: IProductPreview) => {
            this.items.push(productData);
          });
        });
    } else {
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
  public onSubCategoryClicked(subCategoryName: string) {
    this.restService
      .getProducstFromSubCategory(subCategoryName, 0, 10)
      .subscribe({
        next: (data: any) => {
          this.items = [];
          data.forEach((productData: IProductPreview) => {
            this.items.push(productData);
          });
        },
        error: (error: any) => {
          console.error(error);
        },
        complete: () => {
          data.currentSelectedSubCategoryName = subCategoryName;
        },
      });
  }
}
