import {
  Component,
  ElementRef,
  EventEmitter,
  Output,
  ViewChild,
} from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { IProductData } from '../../../../interfaces/IProductData';
import { IUserCartItemDto } from '../../../../interfaces/IUserCartItemDto';
import { CommonModule } from '@angular/common';
import { PaymentComponent } from '../payment/payment.component';
import { EventService } from '../../../../services/event/event.service';
import { data } from '../../../../memory/global-data';

@Component({
  selector: 'app-user-cart',
  imports: [CommonModule, PaymentComponent],
  templateUrl: './user-cart.component.html',
  styleUrl: './user-cart.component.css',
})
export class UserCartComponent {
  @ViewChild('buyButton') buyButtonRef!: ElementRef<HTMLDivElement>;
  @Output() closeComponentEvent = new EventEmitter<void>();

  items: IUserCartItemDto[] = [];
  itemsToDelete: number[] = [];
  totalPrice: number = 0;
  itemCountMap: Map<number, number> = new Map();
  isPaymentPhaseActive: boolean = false;
  isCartConfirmed: boolean = false;

  constructor(
    private restService: RestService,
    private eventService: EventService
  ) {
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
  }
  private saveCartItems() {
    if (this.itemsToDelete.length > 0) {
      this.itemsToDelete.forEach((productId) => {
        this.restService.removeProductFromUserCart(productId).subscribe();
      });
    }
    if (this.itemCountMap.size > 0) {
      this.itemCountMap.forEach((count, productId) => {
        this.restService
          .updateProductCountInUserCart(productId, count)
          .subscribe();
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
    const item = this.items.find((item) => item.productId === productId);
    if (item) {
      if (item.deleteState) {
        item.deleteState = false;
      }

      item.productCount++;
      this.totalPrice += item.productPrice;

      this.itemCountMap.set(item.productId, item.productCount);
    }
  }
  public decreaseProductCount(productId: number) {
    const item = this.items.find((item) => item.productId === productId);

    if (item) {
      if (item.deleteState) {
        this.removeProductFromUserCart(item.productId);
        return;
      }

      item.productCount--;
      if (item.productCount <= 0) {
        item.deleteState = true;
      }

      this.totalPrice -= item.productPrice;

      this.itemCountMap.set(item.productId, item.productCount);
    }
  }
  public openPaymentComponent() {
    if (this.items.length == 0) return;

    if (this.isCartConfirmed) {
      data.totalCartPrice = this.totalPrice;
      this.isPaymentPhaseActive = true;
    } else {
      this.buyButtonRef.nativeElement.style.backgroundColor = 'green';
      this.isCartConfirmed = true;

      this.saveCartItems();
    }
  }
  public closePaymentComponent() {
    this.isPaymentPhaseActive = false;
  }
}
