import { Component, EventEmitter, Output } from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { IProductData } from '../../../../interfaces/IProductData';
import { IUserCartItemDto } from '../../../../interfaces/IUserCartItemDto';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-user-cart',
  imports: [CommonModule],
  templateUrl: './user-cart.component.html',
  styleUrl: './user-cart.component.css',
})
export class UserCartComponent {
  @Output() closeComponentEvent = new EventEmitter<void>();
  items: IUserCartItemDto[] = [];
  itemsToDelete: number[] = [];
  totalPrice: number = 0;
  constructor(private restService: RestService) {
    /* restService.getProductDataForUserCart(1).subscribe((data: IProductData) => {
      console.log(data.productName);
    }); */
    restService.getAllProductsFromUserCart().subscribe({
      next: (data: IUserCartItemDto[]) => {
        this.items = data;
        console.log(data);
      },
      error: (error: any) => {
        console.error(error);
      },
      complete: () => {
        this.items.forEach((item) => {
          this.totalPrice += item.productPrice * item.productCount;
          this.restService
            .getProductImage(item.productId)
            .subscribe((blob: Blob) => {
              const url: string = window.URL.createObjectURL(blob); // Create a URL for the blob
              item.productImageUrl = url;
            });
        });
      },
    });
  }
  ngAfterViewInit() {
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') {
        this.closeCartComponent();
      }
    });
  }
  ngOnDestroy() {
    document.removeEventListener('keydown', (event) => {
      if (event.key === 'Escape') {
        this.closeCartComponent();
      }
    });
    if (this.itemsToDelete.length > 0) {
      this.itemsToDelete.forEach((productId) => {
        this.restService.removeProductFromUserCart(productId).subscribe();
      });
    }
  }
  public closeCartComponent() {
    this.closeComponentEvent.emit();
  }
  public removeProductFromUserCart(productId: number) {
    const itemToRemove = this.items.find(
      (item) => item.productId === productId
    );
    if (itemToRemove) {
      this.totalPrice -= itemToRemove.productPrice * itemToRemove.productCount;
    }
    this.itemsToDelete.push(productId);
    this.items = this.items.filter((item) => item.productId !== productId);
  }
  public increaseProductCount(productId: number) {
    this.items.find((item) => item.productId === productId)!.productCount++;
  }
  public decreaseProductCount(arg0: number) {
    this.items.find((item) => item.productId === arg0)!.productCount--;
  }
}
