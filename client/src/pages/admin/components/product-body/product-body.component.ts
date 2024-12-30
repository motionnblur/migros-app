import { Component, EventEmitter, Output } from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { IProductPreview } from '../../../../interfaces/IProductPreview';
import { CommonModule } from '@angular/common';
import { EventService } from '../../../../services/event/event.service';

@Component({
  selector: 'app-product-body',
  imports: [CommonModule],
  templateUrl: './product-body.component.html',
  styleUrl: './product-body.component.css',
})
export class ProductBodyComponent {
  @Output() hasBodyClicked = new EventEmitter();

  productsData: IProductPreview[] = [];
  private productAddedCallback!: (data: any) => void;
  currentPageNumber: number = 0;

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

  onBodyClick(productId: number) {
    this.hasBodyClicked.emit();
    this.eventService.trigger('productChanged', productId);
  }

  private loadProducts() {
    this.restService.getAllAdminProducts(1, 0, 19).subscribe((data: any) => {
      this.productsData = data;
    });
  }

  deleteProduct(productId: number) {
    this.restService.deleteProduct(productId).subscribe({
      next: () => {
        this.loadProducts();
      },
      error: (error: any) => {
        console.error(error);
      },
    });
  }

  fetchPageLeft() {
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
  fetchPageRight() {
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
