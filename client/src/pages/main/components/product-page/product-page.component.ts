import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { Subscription } from 'rxjs';

import { RestService } from '../../../../services/rest/rest.service';
import { categories, data } from '../../../../memory/global-data';
import { IProductPreview } from '../../../../interfaces/IProductPreview';
import { ISubCategory } from '../../../../interfaces/ISubCategory';
import { ProductPreviewComponent } from '../product-preview/product-preview.component';
import { ProductBuyComponent } from '../product-buy/product-buy.component';
import { ProductPageSwitcherComponent } from '../product-page-switcher/product-page-switcher.component';
import { EventService } from '../../../../services/event/event.service';

@Component({
  selector: 'app-product-page',
  standalone: true,
  imports: [
    ProductPreviewComponent,
    CommonModule,
    ProductBuyComponent,
    ProductPageSwitcherComponent,
  ],
  templateUrl: './product-page.component.html',
  styleUrl: './product-page.component.css',
})
export class ProductPageComponent implements OnInit, OnDestroy {
  items: IProductPreview[] = [];
  hasProductPreviewOpened: boolean = true;
  hasProductBuyViewOpened: boolean = false;
  selectedProductId!: number;
  categoryName: string = '';
  subCategories: ISubCategory[] = [];
  totalProductCount: number = 0;
  currentCategoryId: number = 0;

  private routeSub: Subscription | null = null;
  private lastCategoryId = 0;

  constructor(
    public restService: RestService,
    private route: ActivatedRoute,
    private eventService: EventService
  ) {}

  ngOnInit(): void {
    this.routeSub = this.route.paramMap.subscribe((params) => {
      const categoryIdParam = params.get('categoryId');
      const productIdParam = params.get('productId');

      const categoryId = Number(categoryIdParam);
      if (!Number.isNaN(categoryId)) {
        data.currentSelectedCategoryId = categoryId;
        this.currentCategoryId = categoryId;

        if (this.lastCategoryId !== categoryId) {
          this.lastCategoryId = categoryId;
          data.currentSelectedSubCategoryName = '';
          this.eventService.trigger('resetPageSwitcher');
        }
      }

      if (productIdParam) {
        const productId = Number(productIdParam);
        if (!Number.isNaN(productId)) {
          this.selectedProductId = productId;
          this.hasProductBuyViewOpened = true;
          this.hasProductPreviewOpened = false;
          window.scrollTo({ top: 0, behavior: 'smooth' });
        }
      } else {
        this.hasProductBuyViewOpened = false;
        this.hasProductPreviewOpened = true;
      }

      this.updateCategoryMeta();
      this.loadInitialData();
    });
  }

  ngOnDestroy(): void {
    this.routeSub?.unsubscribe();
  }

  private updateCategoryMeta() {
    const catIndex = data.currentSelectedCategoryId - 1;
    this.categoryName = categories[catIndex] ? categories[catIndex].name : 'Kategori';
  }

  private loadInitialData() {
    this.restService
      .getProductPageData(data.currentSelectedCategoryId, 0, 10)
      .subscribe((res: any) => (this.items = res));

    this.restService
      .getSubCategories(data.currentSelectedCategoryId)
      .subscribe((res: any) => (this.subCategories = res));

    this.restService
      .getProductCountsFromCategory(data.currentSelectedCategoryId)
      .subscribe((res: any) => (this.totalProductCount = res));
  }

  public onSubCategoryClicked(subCategoryName: string) {
    this.eventService.trigger('resetPageSwitcher');
    this.hasProductBuyViewOpened = false;
    this.hasProductPreviewOpened = true;
    this.restService.getProducstFromSubCategory(subCategoryName, 0, 10).subscribe({
      next: (res: any) => {
        this.items = res;
        this.eventService.trigger('setProductCount', res.length + 1);
        data.currentSelectedSubCategoryName = subCategoryName;
      },
    });
  }

  private updateProductList(obs: any) {
    obs.subscribe((res: any) => (this.items = res));
  }

  public changePage(pageNumber: number) {
    const subCat = data.currentSelectedSubCategoryName;
    const obs =
      subCat !== ''
        ? this.restService.getProducstFromSubCategory(subCat, pageNumber - 1, 10)
        : this.restService.getProductPageData(
            data.currentSelectedCategoryId,
            pageNumber - 1,
            10
          );
    this.updateProductList(obs);
  }

  public changePageToFirst() {
    this.changePage(1);
  }

  public changePageToLast(pageCount: number) {
    this.changePage(pageCount);
  }
}
