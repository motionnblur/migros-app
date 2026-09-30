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
import { catchError, forkJoin, map, Observable, of, Subscription } from 'rxjs';
import { ObjectUrlManager } from '../../helpers/object-url-manager';
import {
  calculateCartTotal,
  isSameCartContent,
  resolveCartQuantityChange,
} from '../../helpers/cart-state';

/** A local cart edit that has not reached the server yet. */
type StagedCartEdit =
  | { kind: 'remove'; productId: number }
  | { kind: 'count'; productId: number; count: number };

/** The outcome of one attempt at persisting every staged edit. */
interface StagedEditResult {
  edit: StagedCartEdit;
  saved: boolean;
}

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
  /**
   * A checkout press is in progress: the staged edits are being persisted and the
   * stored cart is then reconciled.
   *
   * <p>True for the whole press, not only for the request, because the two are one
   * operation from the customer's point of view and the button has to stay
   * disabled across both. Confirming anything in between is the defect: the
   * reconciliation would be answering for a cart the staged edits have not
   * reached yet.
   */
  isReconcilingCart: boolean = false;
  /** Staged cart edits are in flight and their outcome is not known yet. */
  isCartWritePending: boolean = false;
  cartMessage = '';
  private destroyed = false;
  /**
   * The cart as it stood when the current checkout press began.
   *
   * <p>Needed to tell two things apart when the response arrives: a change the
   * server made, and a change the customer made while the press was running. The
   * second must not be overwritten by a response that predates it.
   */
  private cartAtPress: IUserCartItemDto[] | null = null;

  /** True while a checkout press may not be repeated. */
  public get isCartBusy(): boolean {
    return this.isReconcilingCart || this.isCartWritePending;
  }

  /**
   * True while the cart rows must not be edited.
   *
   * <p>During a press, because an edit made then is answered by a reconciliation
   * that predates it - the response would describe a cart the customer had
   * already moved on from. During the payment phase, because the reservation has
   * already taken the stored cart, so an edit now would either be discarded or,
   * worse, be written back over a snapshot that is about to be charged for.
   */
  public get isCartLocked(): boolean {
    return this.isCartBusy || this.isPaymentPhaseActive;
  }

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
    this.destroyed = true;
    document.removeEventListener('keydown', this.escHandler);
    this.cartRequest?.unsubscribe();
    this.reconciliationRequest?.unsubscribe();
    this.cancelImageRequests();
    this.releaseProductImages();
    this.persistStagedEditsOnClose();
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

  /**
   * Persists every staged edit and completes only once all of them have.
   *
   * <p>This used to be fire-and-forget: the confirmation path subscribed to the
   * writes and went straight on, so the reconciliation answered for a cart the
   * staged edits had not reached yet and the customer then confirmed a view that
   * no longer matched what was stored. Nothing may be confirmed on the strength
   * of a request that was merely issued, so the caller gets an observable that
   * settles when the server has actually accepted or rejected every edit.
   *
   * <p>An edit is dropped from the staged set only once its own write has
   * succeeded. A rejected one stays staged, so a retry resends exactly what is
   * still unsaved instead of silently discarding a change the customer made, and
   * both backend writers are idempotent (a count rewrite replaces the product's
   * entries, a removal drops them), so resending cannot double anything.
   *
   * <p>A single failed edit fails the batch: the whole batch is the customer's
   * cart, and a partially persisted cart is not a cart anybody confirmed.
   */
  private persistStagedEdits(): Observable<boolean> {
    const edits = this.stagedEdits();
    this.isCartWritePending = edits.length > 0;
    if (edits.length === 0) {
      return of(true);
    }

    const attempts: Observable<StagedEditResult>[] = edits.map((edit) =>
      this.sendStagedEdit(edit).pipe(
        map(() => ({ edit, saved: true })),
        // One refusal is reported, not thrown: the other edits are already in
        // flight and their outcome still has to be collected and honoured.
        catchError(() => of({ edit, saved: false })),
      ),
    );

    return forkJoin(attempts).pipe(
      map((results) => {
        results
          .filter((result) => result.saved)
          .forEach((result) => this.discardStagedEdit(result.edit));
        return results.every((result) => result.saved);
      }),
    );
  }

  private stagedEdits(): StagedCartEdit[] {
    const edits: StagedCartEdit[] = [];
    this.itemsToDelete.forEach((productId) =>
      edits.push({ kind: 'remove', productId }),
    );
    this.itemCountMap.forEach((count, productId) => {
      if (count > 0) {
        edits.push({ kind: 'count', productId, count });
      }
    });
    return edits;
  }

  private sendStagedEdit(edit: StagedCartEdit): Observable<unknown> {
    return edit.kind === 'remove'
      ? this.restService.removeProductFromUserCart(edit.productId)
      : this.restService.updateProductCountInUserCart(edit.productId, edit.count);
  }

  private discardStagedEdit(edit: StagedCartEdit): void {
    if (edit.kind === 'remove') {
      this.itemsToDelete.delete(edit.productId);
    } else {
      this.itemCountMap.delete(edit.productId);
    }
  }

  /**
   * Flushes whatever is still staged when the cart view goes away.
   *
   * <p>Skipped while a write is in flight: that write already carries the staged
   * edits, and a second batch would race the first over the same rows. The
   * in-flight batch is deliberately not cancelled either, so closing the dialog
   * mid-confirmation loses nothing.
   */
  private persistStagedEditsOnClose(): void {
    if (this.isCartWritePending || this.stagedEdits().length === 0) {
      return;
    }
    // Nothing to report it to: the view is gone, and every edit's outcome is
    // already handled inside the batch.
    this.persistStagedEdits().subscribe();
  }

  /**
   * A staged cart edit the server refused.
   *
   * <p>Checkout stays blocked and the edits that were refused stay staged, so the
   * message is not merely a notice: it is the reason the next press repeats the
   * write. Reporting it and then confirming anyway would leave the customer
   * believing a change was saved that was not.
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
    // An edit after a confirmation invalidates it. The cart the customer
    // approved is no longer the cart on screen, and the staged edit has not
    // reached the server, so checkout from here would reserve the old one.
    this.isCartConfirmed = false;
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

    this.isCartConfirmed = false;
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

    this.isCartConfirmed = false;
    const updatedItem = { ...item, productCount: change.quantity };
    this.items = this.items.map((entry) =>
      entry.productId === productId ? updatedItem : entry,
    );
    this.totalPrice -= item.productPrice;
    this.itemCountMap.set(updatedItem.productId, updatedItem.productCount);
  }

  /**
   * First press of the checkout button: persist the staged edits, then reconcile.
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
   *
   * <p>The staged edits are persisted <em>first</em>, and the reconciliation only
   * after they have all succeeded. Reconciling while a write is in flight is the
   * race this whole path exists to remove: the response would describe a cart the
   * server had not accepted yet, and adopting it would either resurrect a
   * removal the customer made or silently discard a quantity they had just set -
   * while the write it raced went on to change the stored cart afterwards.
   *
   * <p>Re-entrant by design: a second press, or a press while the first is still
   * running, is ignored rather than issued against a cart whose writes are still
   * in flight.
   */
  private beginCheckoutReconciliation(): void {
    if (this.isCartBusy) {
      return;
    }
    this.isReconcilingCart = true;
    this.isCartConfirmed = false;
    this.cartMessage = '';
    this.cartAtPress = this.items;

    this.persistStagedEdits().subscribe({
      next: (allSaved) => {
        this.isCartWritePending = false;
        if (this.destroyed) {
          // The view is gone. The writes have landed, and reconciling now would
          // only mutate a destroyed component.
          this.isReconcilingCart = false;
          return;
        }
        if (!allSaved) {
          this.isReconcilingCart = false;
          this.reportCartWriteFailure();
          return;
        }
        this.requestReconciliation();
      },
      error: () => {
        // persistStagedEdits reports a refusal as a result rather than throwing,
        // so this is a backstop. It fails closed all the same.
        this.isCartWritePending = false;
        this.isReconcilingCart = false;
        this.reportCartWriteFailure();
      },
    });
  }

  /**
   * Reconciles the stored cart and adopts the response as the displayed cart.
   *
   * <p>Only ever reached once every staged edit has been persisted, so the
   * response is answering for the cart the customer is looking at.
   */
  private requestReconciliation(): void {
    this.reconciliationRequest?.unsubscribe();
    this.reconciliationRequest = this.restService.reconcileUserCart().subscribe({
      next: (result) => {
        this.reconciliationRequest = null;
        this.isReconcilingCart = false;
        if (!this.destroyed) {
          this.adoptReconciliation(result);
        }
      },
      error: () => {
        this.reconciliationRequest = null;
        this.isReconcilingCart = false;
        if (this.destroyed) {
          return;
        }
        this.isCartConfirmed = false;
        // A failed reconciliation is not a failed cart: leave the customer's
        // view exactly as it was and let them retry deliberately.
        this.cartMessage = 'Sepet dogrulanamadi. Lutfen tekrar deneyin.';
      },
    });
  }

  /**
   * Adopts a reconciled cart, and decides whether the press that triggered it
   * still stands as a confirmation.
   *
   * <p>It does, only when the reconciled cart is what the customer was already
   * looking at. Two independent reports of that are required, and neither is
   * enough on its own: an empty `removedProductIds`/`reducedProductIds` pair only
   * says the server had nothing of its own to repair, and says nothing about
   * whether the cart it returned is the cart on screen.
   */
  private adoptReconciliation(result: ICartReconciliation): void {
    const displayed = this.items;
    const atPress = this.cartAtPress;
    this.cartAtPress = null;

    if (atPress !== null && !isSameCartContent(atPress, displayed)) {
      // The customer edited the cart while this press was running, so the
      // response describes a state that is already older than what they are
      // looking at. Adopting it would drop the edit they just made - a quantity
      // they set, or a row they deleted - and leave the staged copy of it behind
      // to be written later, resurrecting what they removed. The view is left
      // alone instead, and nothing is confirmed: the next press persists the
      // edit first and reconciles a cart that includes it.
      this.isCartConfirmed = false;
      this.cartMessage =
        'Sepetiniz onay sirasinda degisti. Lutfen tekrar kontrol edip onaylayin.';
      return;
    }

    const repaired =
      result.removedProductIds.length > 0 || result.reducedProductIds.length > 0;
    const differs = !isSameCartContent(displayed, result.cart);

    // Whatever the server reports is adopted, not only the case where it repaired
    // something. The view may have been mutated locally, and everything displayed
    // from here on has to be what is actually stored before anything is confirmed.
    this.releaseProductImages();
    this.cancelImageRequests();
    this.items = result.cart;
    this.totalPrice = calculateCartTotal(result.cart);
    this.loadProductImages(result.cart);

    // Nothing is confirmed here, and the staged set is untouched: the edits were
    // persisted before this response was even requested, so there is nothing left
    // to flush and nothing to discard.
    this.isCartConfirmed = false;
    if (repaired) {
      this.cartMessage = describeReconciliation(result);
    } else if (differs) {
      // The response is not the cart the customer pressed confirm on, for a
      // reason the server did not classify - a concurrent removal, a changed
      // price, a clamped quantity. It is still a cart they have not seen, so it
      // is named and re-confirmed rather than approved on their behalf.
      this.cartMessage =
        'Sepetiniz sunucu tarafinda guncellendi. Lutfen sepeti kontrol edip onaylayin.';
    }

    if (!repaired && !differs && this.items.length > 0) {
      // Nothing needed repairing and the response is the cart on screen, so the
      // confirmation the customer already gave stands. Checkout stays one press
      // away and never degrades into three.
      this.markCartConfirmed();
    }
  }

  private markCartConfirmed(): void {
    this.isCartConfirmed = true;
    const button = this.buyButtonRef?.nativeElement as
      | HTMLButtonElement
      | undefined;
    if (button) {
      button.style.backgroundColor = 'green';
    }
  }

  /**
   * Reconciles from the empty view, where the checkout button is disabled.
   *
   * <p>An empty view is not necessarily an empty cart: the read hides what it
   * cannot render, so a cart whose every entry became unbuyable is drawn exactly
   * like one the customer emptied. Checkout is unreachable from an empty cart, so
   * without a reachable action here that residue is never reconciled and the ids
   * stay stored forever. This is the only way to tell the two apart; the server's
   * report says whether anything was actually there.
   */
  public reconcileEmptyCart(): void {
    if (this.isCartBusy) {
      return;
    }
    this.beginCheckoutReconciliation();
  }

  public openPaymentComponent() {
    if (this.isCartBusy) {
      // A press while the staged writes or the reconciliation are still running
      // would either confirm a cart nobody has seen the outcome of, or open
      // checkout against writes that have not landed. Both are the same defect.
      return;
    }

    if (this.items.length === 0) {
      this.beginCheckoutReconciliation();
      return;
    }

    if (this.items.some((item) => item.productCount > item.availableStock)) {
      alert(
        'Sepetteki bir veya daha fazla urunun stogu yetersiz. Lutfen sepeti guncelleyin.',
      );
      return;
    }

    if (this.isCartConfirmed) {
      // Display only. The chargeable amount is the server's own checkout
      // snapshot, computed under the same locks that reserve the stock.
      data.totalCartPrice = this.totalPrice;
      this.isPaymentPhaseActive = true;
    } else {
      this.beginCheckoutReconciliation();
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
