import {
  Component,
  ElementRef,
  EventEmitter,
  OnDestroy,
  Output,
  ViewChild,
} from '@angular/core';
import { RestService } from '../../../../services/rest/rest.service';
import { IUserCartItemDto } from '../../../../interfaces/IUserCartItemDto';
import { CommonModule } from '@angular/common';
import { PaymentComponent } from '../payment/payment.component';
import { data } from '../../../../memory/global-data';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { ObjectUrlManager } from '../../helpers/object-url-manager';
import {
  calculateCartTotal,
  resolveCartQuantityChange,
} from '../../helpers/cart-state';

@Component({
  selector: 'app-user-cart',
  standalone: true,
  imports: [CommonModule, PaymentComponent],
  templateUrl: './user-cart.component.html',
  styleUrl: './user-cart.component.css',
})
export class UserCartComponent implements OnDestroy {
  @ViewChild('buyButton') buyButtonRef!: ElementRef<HTMLButtonElement>;

  items: IUserCartItemDto[] = [];
  private readonly itemsToDelete = new Set<number>();
  private readonly itemCountMap = new Map<number, number>();
  private readonly productImageUrls = new Set<ObjectUrlManager>();
  private imageRequests: Subscription[] = [];
  private cartRequest: Subscription | null = null;
  totalPrice = 0;
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
    private route: ActivatedRoute,
  ) {
    this.loadCart();
  }

  ngAfterViewInit() {
    document.addEventListener('keydown', this.escHandler);
  }

  ngOnDestroy(): void {
    document.removeEventListener('keydown', this.escHandler);
    this.cartRequest?.unsubscribe();
    this.cancelImageRequests();
    this.releaseProductImages();
    this.saveCartItems();
  }

  private loadCart() {
    this.cartRequest?.unsubscribe();
    this.cancelImageRequests();
    this.cartRequest = this.restService.getAllProductsFromUserCart().subscribe({
      next: (data: IUserCartItemDto[]) => {
        this.releaseProductImages();
        this.items = data;
        this.totalPrice = calculateCartTotal(data);

        this.items.forEach((item) => {
          const imageRequest = this.restService
            .getProductImage(item.productId)
            .subscribe((blob: Blob) => {
              const imageUrl = new ObjectUrlManager();
              this.productImageUrls.add(imageUrl);
              item.productImageUrl = imageUrl.create(blob);
            });
          this.imageRequests.push(imageRequest);
        });
      },
      error: (error: unknown) => {
        console.error(error);
      },
    });
  }

  private cancelImageRequests(): void {
    this.imageRequests.forEach((request) => request.unsubscribe());
    this.imageRequests = [];
  }

  private releaseProductImages(): void {
    this.productImageUrls.forEach((imageUrl) => imageUrl.release());
    this.productImageUrls.clear();
  }

  private saveCartItems() {
    this.itemsToDelete.forEach((productId) => {
      this.restService.removeProductFromUserCart(productId).subscribe();
    });

    this.itemCountMap.forEach((count, productId) => {
      if (count > 0) {
        this.restService
          .updateProductCountInUserCart(productId, count)
          .subscribe();
      }
    });

    this.itemsToDelete.clear();
    this.itemCountMap.clear();
  }

  public closeCartComponent() {
    this.router.navigate([{ outlets: { modal: null } }], {
      relativeTo: this.route.parent ?? this.route,
    });
  }

  public removeProductFromUserCart(productId: number): void {
    const itemToRemove = this.items.find(
      (item) => item.productId === productId,
    );
    if (itemToRemove) {
      this.totalPrice -= itemToRemove.productPrice * itemToRemove.productCount;
    }

    if (!this.itemsToDelete.has(productId)) {
      this.itemsToDelete.add(productId);
    }

    this.itemCountMap.delete(productId);
    this.items = this.items.filter((item) => item.productId !== productId);
  }

  public increaseProductCount(productId: number): void {
    const item = this.items.find((entry) => entry.productId === productId);
    const change = resolveCartQuantityChange(item, 'increase');
    if (change.kind === 'stock-limit') {
      alert(`Bu urunden en fazla ${change.availableStock} adet alabilirsiniz.`);
      return;
    }
    if (change.kind !== 'update' || !item) {
      return;
    }

    const updatedItem = { ...item, productCount: change.quantity, deleteState: false };
    this.items = this.items.map((entry) =>
      entry.productId === productId ? updatedItem : entry,
    );
    this.totalPrice += item.productPrice;
    this.itemCountMap.set(updatedItem.productId, updatedItem.productCount);
  }

  public decreaseProductCount(productId: number): void {
    const item = this.items.find((entry) => entry.productId === productId);
    const change = resolveCartQuantityChange(item, 'decrease');
    if (change.kind === 'missing' || !item) {
      return;
    }

    if (change.kind === 'remove') {
      this.removeProductFromUserCart(productId);
      return;
    }

    if (change.kind !== 'update') {
      return;
    }

    const updatedItem = { ...item, productCount: change.quantity };
    this.items = this.items.map((entry) =>
      entry.productId === productId ? updatedItem : entry,
    );
    this.totalPrice -= item.productPrice;
    this.itemCountMap.set(updatedItem.productId, updatedItem.productCount);
  }

  public openPaymentComponent() {
    if (this.items.length === 0) {
      return;
    }

    if (this.items.some((item) => item.productCount > item.availableStock)) {
      alert(
        'Sepetteki bir veya daha fazla urunun stogu yetersiz. Lutfen sepeti guncelleyin.',
      );
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

  public handleCheckoutPrepared() {
    // The server reserved and removed the cart contents when the snapshot was
    // prepared, so the live cart view must be refreshed from the backend.
    this.itemsToDelete.clear();
    this.itemCountMap.clear();
    this.isCartConfirmed = false;
    this.loadCart();
  }

  public handlePaymentSuccess() {
    this.isPaymentPhaseActive = false;
    this.isCartConfirmed = false;
    this.items = [];
    this.itemsToDelete.clear();
    this.itemCountMap.clear();
    this.totalPrice = 0;
    this.router.navigate([{ outlets: { modal: ['order-tracker'] } }], {
      relativeTo: this.route.parent ?? this.route,
    });
  }
}
