import { Component, EventEmitter, Output } from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { CommonModule } from '@angular/common';
import { EventService } from '../../../../services/event/event.service';
import { IAdminProductPreview } from '../../../../interfaces/IAdminProductPreview';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { categories } from '../../../../memory/global-data';

@Component({
  selector: 'app-product-body',
  imports: [CommonModule, MatButtonModule, MatMenuModule],
  templateUrl: './product-body.component.html',
  styleUrl: './product-body.component.css',
})
export class ProductBodyComponent {
  @Output() hasBodyClicked = new EventEmitter();

  productsData: IAdminProductPreview[] = [];
  private productAddedCallback!: (data: any) => void;
  currentPageNumber: number = 0;
  categories = categories;

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
    this.restService
      .getAllAdminProducts(1, 0, 19)
      .subscribe((data: IAdminProductPreview[]) => {
        this.productsData = data;
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

  public fetchPageLeft() {
    this.restService
      .getAllAdminProducts(1, this.currentPageNumber - 1, 19)
      .subscribe({
        next: (data: any) => {
          this.productsData = data;
        },
        error: (error: any) => {
          console.error(error);
        },
        complete: () => {
          console.log('Completed');
          this.currentPageNumber -= 1;
        },
      });
  }
  public fetchPageRight() {
    this.restService
      .getAllAdminProducts(1, this.currentPageNumber + 1, 19)
      .subscribe({
        next: (data: any) => {
          this.productsData = data;
        },
        error: (error: any) => {
          console.error(error);
        },
        complete: () => {
          console.log('Completed');
          this.currentPageNumber += 1;
        },
      });
  }
}
