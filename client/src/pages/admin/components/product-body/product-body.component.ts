import { Component, EventEmitter, Output } from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { CommonModule } from '@angular/common';
import { EventService } from '../../../../services/event/event.service';
import { IAdminProductPreview } from '../../../../interfaces/IAdminProductPreview';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { categories, data } from '../../../../memory/global-data';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';

@Component({
  selector: 'app-product-body',
  imports: [CommonModule, MatButtonModule, MatMenuModule, MatPaginatorModule],
  templateUrl: './product-body.component.html',
  styleUrl: './product-body.component.css',
})
export class ProductBodyComponent {
  @Output() hasBodyClicked = new EventEmitter();
  @Output() hasAddProductButtonClicked = new EventEmitter();

  productsData: IAdminProductPreview[] = [];
  private productAddedCallback!: (data: any) => void;
  currentPageNumber: number = 0;
  categories = categories;
  public selectedCategoryName: string = 'Please select a category';
  public productPageLength: number = 1;
  currentPageSize: number = 5;

  constructor(
    private restService: RestService,
    private eventService: EventService
  ) {
    this.productAddedCallback = () => {
      this.loadProducts();
    };
  }
  ngOnInit() {
    this.loadProducts();
    this.eventService.on('productAdded', this.productAddedCallback);
  }
  ngOnDestroy() {
    this.eventService.off('productAdded', this.productAddedCallback);
  }

  public onBodyClick(productId: number) {
    this.hasBodyClicked.emit();
    this.eventService.trigger('productChanged', productId);
  }

  private loadProducts() {
    const id = data.currentSelectedCategoryId;
    this.restService.getProductPageData(id, 0, this.currentPageSize).subscribe({
      next: (data: any) => {
        this.productsData = data;
      },
      error: (error: any) => {
        console.error(error);
      },
      complete: () => {
        this.selectedCategoryName = this.categories[id - 1].name;
      },
    });
  }

  public deleteProduct(productId: number) {
    this.restService.deleteProduct(productId).subscribe({
      next: () => {},
      error: (error: any) => {
        console.error(error);
      },
      complete: () => {
        this.productsData = this.productsData.filter(
          (item) => item.productId !== productId
        );
      },
    });
  }
  public onCategorySelected(index: number) {
    data.currentSelectedCategoryId = index;

    this.restService
      .getProductPageData(index, 0, this.currentPageSize)
      .subscribe({
        next: (data: any) => {
          this.productsData = data;
        },
        error: (error: any) => {
          console.error(error);
        },
        complete: () => {
          this.selectedCategoryName = this.categories[index - 1].name;
        },
      });

    this.restService
      .getProductCountsFromCategory(data.currentSelectedCategoryId)
      .subscribe({
        next: (data: any) => {
          this.productPageLength = data;
        },
        error: (error: any) => {
          console.error(error);
        },
        complete: () => {
          console.log('completed');
        },
      });
  }
  pageEvent($event: PageEvent) {
    this.currentPageSize = $event.pageSize;

    this.restService
      .getProductPageData(
        data.currentSelectedCategoryId,
        $event.pageIndex,
        $event.pageSize
      )
      .subscribe({
        next: (data: any) => {
          this.productsData = data;
        },
        error: (error: any) => {
          console.error(error);
        },
        complete: () => {
          this.currentPageNumber = $event.pageIndex;
        },
      });
  }
  public openProductAdder() {
    this.hasAddProductButtonClicked.emit();
  }
}
