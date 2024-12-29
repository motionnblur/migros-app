import { Component, EventEmitter, Output } from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { IItemPreview } from '../../../../interfaces/IItemPreview';
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

  productsData: IItemPreview[] = [];
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

  onBodyClick(itemId: number) {
    this.hasBodyClicked.emit();
    this.eventService.trigger('productChanged', itemId);
  }

  private loadProducts() {
    this.restService.getAllAdminProducts(1, 0, 19).subscribe((data: any) => {
      this.productsData = data;
    });
  }

  deleteProduct(itemId: number) {
    this.restService.deleteProduct(itemId).subscribe({
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
