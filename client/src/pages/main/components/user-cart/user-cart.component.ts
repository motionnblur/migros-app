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
import { ICartReconciliation } from '../../../../interfaces/ICartReconciliation';
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
  private reconciliationRequest: Subscription | null = null;
  totalPrice = 0;
  isPaymentPhaseActive: boolean = false;
  isCartConfirmed: boolean = false;
  isReconcilingCart: boolean = false;
  cartMessage = '';

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
    this.reconciliationRequest?.unsubscribe();
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
        this.loadProductImages(data);
      },
      error: (error: unknown) => {
        console.error(error);
      },
    });
  }

  private loadProductImages(data: IUserCartItemDto[]): void {
    data.forEach((item) => {
      const imageRequest = this.restService
        .getProductImage(item.productId)
        .subscribe({
          next: (blob: Blob) => {
            const imageUrl = new ObjectUrlManager();
            this.productImageUrls.add(imageUrl);
            item.productImageUrl = imageUrl.create(blob);
          },
          // A product can be deleted while it sits in a cart, and then it has
          // no image to serve. The row still renders from the placeholder, so a
          // 404 here is not worth an unhandled error escaping the component.
          error: () => {},
        });
      this.imageRequests.push(imageRequest);
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
      this.restService
        .removeProductFromUserCart(productId)
        .subscribe({ error: () => this.reportCartWriteFailure() });
    });

    this.itemCountMap.forEach((count, productId) => {
      if (count > 0) {
        this.restService
          .updateProductCountInUserCart(productId, count)
          .subscribe({ error: () => this.reportCartWriteFailure() });
      }
    });

    this.itemsToDelete.clear();
    this.itemCountMap.clear();
  }

  /**
   * A staged cart edit the server refused.
   *
   * <p>Reconciliation is what makes this reachable: a quantity staged before a
   * reconciling press can exceed the stock the reconciliation just clamped to,
   * and the server refuses it. The outcome is still consistent - the reconciled
   * cart is what gets displayed and what gets reserved - but a write that failed
   * in silence would leave the customer believing a change was saved that was
   * not, so it is surfaced instead.
   */
  private reportCartWriteFailure(): void {
    this.cartMessage = 'Sepet guncellemesi kaydedilemedi. Lutfen tekrar deneyin.';
  }

  public closeCartComponent() {
    this.router.navigate([{ outlets: { modal: null } }], {
      relativeTo: this.route.parent ?? this.route,
    });
  }

  public removeProductFromUserCart(productId: number): void {
    // A reconciliation notice describes the cart as it was reconciled. Once the
    // customer starts editing again it no longer describes what they are looking
    // at, so it is cleared rather than left to misinform the next decision.
    this.cartMessage = '';
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
    this.cartMessage = '';
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
    this.cartMessage = '';
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

  /**
   * First press of the checkout button reconciles the stored cart.
   *
   * <p>The cart read is a pure read and hides entries it cannot render, while
   * checkout reserves from the stored list. A cart holding a product that was
   * deleted or sold out therefore looks complete here and then fails at checkout
   * for a line that was never displayed - and with no row, there was no way to
   * remove it. Reconciling here closes that: the server drops what cannot be
   * bought and lowers what exceeds the remaining stock, under the same row lock
   * every other cart writer uses, so a concurrent add is preserved rather than
   * erased.
   *
   * <p>It runs before the customer's second confirmation rather than instead of
   * it. If anything changed, the cart is re-rendered and the customer has to
   * confirm the new contents, so no quantity is ever charged for that they did
   * not see and approve.
   */
  private reconcileCartBeforeCheckout(): void {
    if (this.isReconcilingCart) {
      return;
    }
    this.isReconcilingCart = true;
    this.cartMessage = '';
    this.reconciliationRequest = this.restService.reconcileUserCart().subscribe({
      next: (result) => {
        this.isReconcilingCart = false;
        const changed = result.removedProductIds.length > 0 || result.reducedProductIds.length > 0;
        // Anything the server reports is adopted, not only the case where it
        // repaired something. A view that has been mutated locally - a row the
        // customer removed, or a quantity they changed - still has to end up
        // showing what is actually stored before anything is confirmed, or they
        // would be asked to approve a cart that no longer exists on the server.
        // The confirmation below is what persists those local edits.
        this.releaseProductImages();
        this.cancelImageRequests();
        this.items = result.cart;
        this.totalPrice = calculateCartTotal(result.cart);
        this.loadProductImages(result.cart);
        if (changed) {
          this.cartMessage = describeReconciliation(result);
        }

        this.isCartConfirmed = false;
        if (!changed && this.items.length > 0) {
          // Nothing needed repairing, so the confirmation the customer already
          // gave stands. The staged local edits are left in place and flushed by
          // saveCartItems() below, which is what persists them.
          const button = this.buyButtonRef?.nativeElement as
            | HTMLButtonElement
            | undefined;
          if (button) {
            button.style.backgroundColor = 'green';
          }
          this.isCartConfirmed = true;
          this.saveCartItems();
        } else {
          // The cart changed underneath the view, so a staged quantity is no
          // longer a delta against anything meaningful and persisting it would
          // resurrect a value the server has just declared unsellable. The
          // customer re-confirms from the reconciled cart instead.
          this.itemsToDelete.clear();
          this.itemCountMap.clear();
        }
      },
      error: () => {
        this.isReconcilingCart = false;
        this.isCartConfirmed = false;
        // A failed reconciliation is not a failed cart: leave the customer's
        // view exactly as it was and let them retry deliberately.
        this.cartMessage = 'Sepet dogrulanamadi. Lutfen tekrar deneyin.';
      },
    });
  }

  public openPaymentComponent() {
    if (this.items.length === 0) {
      // The stored cart is not necessarily empty, only what it can render.
      // A cart whose every entry became unbuyable looks identical to a cart the
      // customer emptied, and without this the residue is never reconciled: the
      // view can never trigger the repair, so the ids stay stored indefinitely.
      // Reconciling is the only way to tell the two apart, and it reports back
      // whether anything was actually there to remove.
      this.reconcileCartBeforeCheckout();
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
      this.reconcileCartBeforeCheckout();
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
    this.cartMessage = '';
    this.loadCart();
  }

  public handlePaymentSuccess() {
    this.isPaymentPhaseActive = false;
    this.isCartConfirmed = false;
    this.items = [];
    this.itemsToDelete.clear();
    this.itemCountMap.clear();
    this.totalPrice = 0;
    this.cartMessage = '';
    this.router.navigate([{ outlets: { modal: ['order-tracker'] } }], {
      relativeTo: this.route.parent ?? this.route,
    });
  }
}

/**
 * Explains a reconciliation to the customer in their own terms.
 *
 * <p>Silently dropping lines from someone's order is its own defect, so the two
 * outcomes are named separately: a product that can no longer be bought at all
 * versus one whose quantity was lowered to what is left.
 */
function describeReconciliation(result: ICartReconciliation): string {
  const parts: string[] = [];
  if (result.removedProductIds.length > 0) {
    parts.push(
      `${result.removedProductIds.length} urun artik satista olmadigi icin sepetten cikarildi.`,
    );
  }
  if (result.reducedProductIds.length > 0) {
    parts.push(
      `${result.reducedProductIds.length} urunun miktari kalan stoga gore azaltildi.`,
    );
  }
  return `${parts.join(' ')} Lutfen sepeti kontrol edip onaylayin.`;
}
