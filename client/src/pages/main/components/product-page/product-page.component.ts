import {Component, ElementRef, OnInit, ViewChild, OnDestroy} from '@angular/core';
import {RestService} from '../../../../services/rest/rest.service';
import {EventService} from '../../../../services/event/event.service';
import {categories, data} from '../../../../memory/global-data';
import {IProductPreview} from '../../../../interfaces/IProductPreview';
import {ISubCategory} from '../../../../interfaces/ISubCategory';
import {ProductPreviewComponent} from '../product-preview/product-preview.component';
import {CommonModule} from '@angular/common';
import {ProductBuyComponent} from '../product-buy/product-buy.component';
import {ProductPageSwitcherComponent} from '../product-page-switcher/product-page-switcher.component';

@Component({
  selector: 'app-product-page',
  standalone: true, // or check if this exists
  imports: [
    ProductPreviewComponent, // 2. Add it here!
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

  private onProductPreviewClickedCallback: (productId: number) => void;

  constructor(
    public restService: RestService,
    private eventService: EventService
  ) {
    this.onProductPreviewClickedCallback = this.openProductBuyView.bind(this);

    // Check if category index is valid
    const catIndex = data.currentSelectedCategoryId - 1;
    this.categoryName = categories[catIndex] ? categories[catIndex].name : 'Kategori';
  }

  ngOnInit(): void {
    this.loadInitialData();
    this.eventService.on('onProductPreviewClicked', this.onProductPreviewClickedCallback);
  }

  ngOnDestroy(): void {
    this.eventService.off('onProductPreviewClicked', this.onProductPreviewClickedCallback);
  }

  private loadInitialData() {
    // Fetch products
    this.restService.getProductPageData(data.currentSelectedCategoryId, 0, 10)
      .subscribe((res: any) => this.items = res);

    // Fetch subcategories
    this.restService.getSubCategories(data.currentSelectedCategoryId)
      .subscribe((res: any) => this.subCategories = res);

    // Fetch count
    this.restService.getProductCountsFromCategory(data.currentSelectedCategoryId)
      .subscribe((res: any) => this.totalProductCount = res);
  }

  private openProductBuyView(productId: number) {
    this.selectedProductId = productId;
    this.hasProductBuyViewOpened = true;
    this.hasProductPreviewOpened = false;
    // Window scroll to top so user sees the top of the product details
    window.scrollTo({top: 0, behavior: 'smooth'});
  }

  public onSubCategoryClicked(subCategoryName: string) {
    this.eventService.trigger('resetPageSwitcher');
    this.restService.getProducstFromSubCategory(subCategoryName, 0, 10).subscribe({
      next: (res: any) => {
        this.items = res;
        this.eventService.trigger('setProductCount', res.length + 1);
        data.currentSelectedSubCategoryName = subCategoryName;
      }
    });
  }

  // Common pagination logic helper
  private updateProductList(obs: any) {
    obs.subscribe((res: any) => this.items = res);
  }

  public changePage(pageNumber: number) {
    const subCat = data.currentSelectedSubCategoryName;
    const obs = subCat !== ''
      ? this.restService.getProducstFromSubCategory(subCat, pageNumber - 1, 10)
      : this.restService.getProductPageData(data.currentSelectedCategoryId, pageNumber - 1, 10);
    this.updateProductList(obs);
  }

  public changePageToFirst() {
    this.changePage(1);
  }

  public changePageToLast(pageCount: number) {
    this.changePage(pageCount);
  }
}
