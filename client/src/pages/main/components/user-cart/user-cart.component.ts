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
  imageUrls: string[] | null = [];
  items: IUserCartItemDto[] = [];
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
          this.restService
            .getProductImage(item.productId)
            .subscribe((blob: Blob) => {
              const url: string = window.URL.createObjectURL(blob); // Create a URL for the blob
              this.imageUrls?.push(url);
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
  }
  public closeCartComponent() {
    this.closeComponentEvent.emit();
  }
}
