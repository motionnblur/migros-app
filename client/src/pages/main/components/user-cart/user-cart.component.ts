import {
  Component,
  ElementRef,
  EventEmitter,
  Output,
  ViewChild,
} from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { IUserCartItemDto } from '../../../../interfaces/IUserCartItemDto';
import { CommonModule } from '@angular/common';
import { PaymentComponent } from '../payment/payment.component';
import { data } from '../../../../memory/global-data';
import { ActivatedRoute, Router } from '@angular/router';

@Component({
  selector: 'app-user-cart',
  standalone: true,
  imports: [CommonModule, PaymentComponent],
  templateUrl: './user-cart.component.html',
  styleUrl: './user-cart.component.css',
})
export class UserCartComponent {
  @ViewChild('buyButton') buyButtonRef!: ElementRef<HTMLButtonElement>;
  @Output() closeComponentEvent = new EventEmitter<void>();

  items: IUserCartItemDto[] = [];
  itemsToDelete: number[] = [];
  totalPrice: number = 0;
  itemCountMap: Map<number, number> = new Map();
  isPaymentPhaseActive: boolean = false;
  isCartConfirmed: boolean = false;

  private readonly escHandler = (event: KeyboardEvent) => {
    if (event.key === 'Escape') {
      this.closeCartComponent();
    }
  };

  constructor(
    private restService: RestService,
    private router: Router,
    private route: ActivatedRoute
  ) {
    this.loadCart();
  }

  ngAfterViewInit() {
    document.addEventListener('keydown', this.escHandler);
  }

  ngOnDestroy() {
    document.removeEventListener('keydown', this.escHandler);
    this.saveCartItems();
  }

  private loadCart() {
    this.restService.getAllProductsFromUserCart().subscribe({
      next: (data: IUserCartItemDto[]) => {
        this.items = data;
        this.totalPrice = this.calculateTotal(data);

        this.items.forEach((item) => {
          this.restService.getProductImage(item.productId).subscribe((blob: Blob) => {
            const url: string = window.URL.createObjectURL(blob);
            item.productImageUrl = url;
          });
        });
      },
      error: (error: any) => {
        console.error(error);
      },
    });
  }

  private calculateTotal(items: IUserCartItemDto[]): number {
    return items.reduce(
      (total, item) => total + item.productPrice * item.productCount,
      0
    );
  }

  private saveCartItems() {
    this.itemsToDelete.forEach((productId) => {
      this.restService.removeProductFromUserCart(productId).subscribe();
    });

    this.itemCountMap.forEach((count, productId) => {
      if (count > 0) {
        this.restService.updateProductCountInUserCart(productId, count).subscribe();
      }
    });

    this.itemsToDelete = [];
    this.itemCountMap.clear();
  }

  public closeCartComponent() {
    this.router.navigate([{ outlets: { modal: null } }], {
      relativeTo: this.route.parent ?? this.route,
    });
    this.closeComponentEvent.emit();
  }

  public removeProductFromUserCart(productId: number) {
    const itemToRemove = this.items.find((item) => item.productId === productId);
    if (itemToRemove) {
      this.totalPrice -= itemToRemove.productPrice * itemToRemove.productCount;
    }

    if (!this.itemsToDelete.includes(productId)) {
      this.itemsToDelete.push(productId);
    }

    this.itemCountMap.delete(productId);
    this.items = this.items.filter((item) => item.productId !== productId);
  }

  public increaseProductCount(productId: number) {
    const item = this.items.find((entry) => entry.productId === productId);
    if (!item) {
      return;
    }

    if (item.productCount >= item.availableStock) {
      alert(`Bu urunden en fazla ${item.availableStock} adet alabilirsiniz.`);
      return;
    }

    item.productCount++;
    item.deleteState = false;
    this.totalPrice += item.productPrice;
    this.itemCountMap.set(item.productId, item.productCount);
  }

  public decreaseProductCount(productId: number) {
    const item = this.items.find((entry) => entry.productId === productId);
    if (!item) {
      return;
    }

    if (item.productCount <= 1) {
      this.removeProductFromUserCart(item.productId);
      return;
    }

    item.productCount--;
    this.totalPrice -= item.productPrice;
    this.itemCountMap.set(item.productId, item.productCount);
  }

  public openPaymentComponent() {
    if (this.items.length === 0) {
      return;
    }

    if (this.items.some((item) => item.productCount > item.availableStock)) {
      alert('Sepetteki bir veya daha fazla urunun stogu yetersiz. Lutfen sepeti guncelleyin.');
      return;
    }

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

  public handlePaymentSuccess() {
    this.isPaymentPhaseActive = false;
    this.isCartConfirmed = false;
    this.items = [];
    this.itemsToDelete = [];
    this.itemCountMap.clear();
    this.totalPrice = 0;
    this.router.navigate([{ outlets: { modal: ['order-tracker'] } }], {
      relativeTo: this.route.parent ?? this.route,
    });
  }
}
