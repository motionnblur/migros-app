import {
  AfterViewInit,
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
import {
  capturePreviousFocus,
  containTabKey,
  focusDialog,
  restoreFocus,
} from '../payment/dialog-a11y';
import { formatAmount, formatMoney } from '../../helpers/money-format';
import { data } from '../../../../memory/global-data';
import { ActivatedRoute, Router } from '@angular/router';
import {
  catchError,
  finalize,
  forkJoin,
  map,
  Observable,
  of,
  shareReplay,
  Subscription,
  take,
  timeout,
} from 'rxjs';
import { ObjectUrlManager } from '../../helpers/object-url-manager';
import {
  calculateCartTotal,
  isSameCartContent,
  resolveCartQuantityChange,
} from '../../helpers/cart-state';
import { CartStagedEditsHost } from '../../../../app/guards/cart-staged-edits.guard';

/** A local cart edit that has not reached the server yet. */
type StagedCartEdit =
  | { kind: 'remove'; productId: number }
  | { kind: 'count'; productId: number; count: number };

/** The outcome of one attempt at persisting every staged edit. */
interface StagedEditResult {
  edit: StagedCartEdit;
  saved: boolean;
}

/**
 * How long a single cart write may take before it is treated as having failed.
 *
 * <p>Without a bound, a request that never comes back leaves the cart pinned in
 * its "saving" state forever: the rows stay locked, checkout stays unreachable
 * and the customer has no way to tell "still saving" from "lost". Twenty seconds
 * is far longer than these writes take against the real API, so it fires only for
 * a connection that is genuinely gone.
 *
 * <p>It is safe to resend after one: both backend writers are idempotent - a
 * count rewrite replaces the product's entries and a removal drops them - so a
 * retry of an uncertain result converges on the same stored cart rather than
 * doubling anything. The uncertain case is therefore reported as a failed write
 * and the edit stays staged, which is the same treatment a definite refusal gets.
 */
const CART_WRITE_TIMEOUT_MS = 20_000;

/**
 * Every customer-visible string in the cart dialog, in one place.
 *
 * <p>The dialog was written without Turkish diacritics - `guncellendi`,
 * `cikarildi`, `Lutfen` - so a customer was told their cart had been "guncellendi"
 * in a storefront whose every other surface spells those words properly. Turkish
 * is corrected here rather than at each throw site, both so the wording is one
 * edit rather than several and so a test can pin it without duplicating it.
 */
export const CART_COPY = {
  title: 'Sepetim',
  close: 'Sepeti kapat',
  emptyTitle: 'Sepetiniz henüz boş.',
  emptyHint:
    'Satışta olmayan ürünler sepetinizden otomatik olarak çıkarılmaz. Sepetinizi kontrol ederek temizleyebilirsiniz.',
  emptyAction: 'Sepeti Kontrol Et',
  totalLabel: 'Toplam Tutar',
  /** The currency the cart's own arithmetic is in; the total is formatted whole. */
  currencyLabel: 'TL',
  stockLabel: 'Stok',
  removeFromCart: (productName: string): string =>
    `Sepetten çıkar: ${productName}`,
  decreaseQuantity: (productName: string): string =>
    `Adedi azalt: ${productName}`,
  increaseQuantity: (productName: string): string =>
    `Adedi artır: ${productName}`,
  quantityGroup: (productName: string): string => `${productName} adedi`,
  writeFailed: 'Sepet güncellemesi kaydedilemedi. Lütfen tekrar deneyin.',
  reconcileFailed: 'Sepet doğrulanamadı. Lütfen tekrar deneyin.',
  changedDuringPress:
    'Sepetiniz onay sırasında değişti. Lütfen tekrar kontrol edip onaylayın.',
  serverUpdated:
    'Sepetiniz sunucu tarafında güncellendi. Lütfen sepeti kontrol edip onaylayın.',
  insufficientStock:
    'Sepetteki bir veya daha fazla ürünün stoğu yetersiz. Lütfen sepeti güncelleyin.',
  saving: 'Sepet kaydediliyor...',
  reconciling: 'Sepet kontrol ediliyor...',
  confirm: 'Sepeti Onayla',
  checkout: 'Siparişi Tamamla',
  empty: 'Sepet Boş',
} as const;

/** The only currency the cart's own arithmetic is denominated in. */
const CART_CURRENCY = 'TRY';

/**
 * Explains a stock limit in the customer's own terms.
 *
 * <p>The number is the available stock, not the count they are holding, so the
 * sentence names the ceiling the server would enforce anyway.
 */
function describeStockLimit(availableStock: number): string {
  return `Bu üründen en fazla ${availableStock} adet alabilirsiniz.`;
}

@Component({
  selector: 'app-user-cart',
  standalone: true,
  imports: [CommonModule, PaymentComponent],
  templateUrl: './user-cart.component.html',
  styleUrl: './user-cart.component.css',
})
export class UserCartComponent
  implements AfterViewInit, OnDestroy, CartStagedEditsHost
{
  @ViewChild('buyButton') buyButtonRef!: ElementRef<HTMLButtonElement>;
  /** The dialog surface itself, the element that owns `role="dialog"`. */
  @ViewChild('cartDialog') cartDialogRef?: ElementRef<HTMLElement>;

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
  /**
   * True while a close has been asked for but could not be carried out yet
   * because a batch of staged writes was already in flight.
   *
   * <p>The close is deferred rather than dropped or forced: navigating away now
   * would leave those writes to land against a view the customer can no longer
   * see or correct, and starting a second batch would race the first over the
   * same rows. Whichever batch is running settles it instead - see
   * `settleWriteBatch` and `settleFailedWriteBatch`.
   *
   * <p>It describes that one batch and nothing else. A request recorded against a
   * batch is honored when the writes land and void when they do not; it is never
   * carried forward into a later press, which would turn one customer's failed
   * save into a dismissal on a checkout they had not asked to abandon.
   */
  private closeRequestedWhileWritePending: boolean = false;
  /**
   * True while a router navigation outside the cart's own control is being held
   * by the route's CanDeactivate guard.
   *
   * <p>It exists because the guarded navigation and a close are not the same
   * departure. The router performs an external navigation itself, and only to the
   * destination the customer asked for; a close is the cart's own decision and
   * always goes to the page behind the cart. Letting a write's completion
   * callback navigate while the guard is holding a navigation would replace that
   * destination with the cart's idea of where to go, and cancelling the very
   * navigation that was waiting. So the two are recorded separately: while this
   * is set the write-completion callbacks do not navigate and do not reconcile,
   * and the guard's own answer lets the router finish its move.
   *
   * <p>It is cleared when the batch settles - saved, refused or timed out - and
   * when the guard itself is cancelled, so a navigation that never happened
   * cannot suppress a later close.
   */
  private externalNavigationPending: boolean = false;
  /**
   * The batch of staged writes currently in flight, or `null` when none is.
   *
   * <p>Also how a second observer joins a batch that already exists instead of
   * starting a competing one. Both the component's own close path and the router
   * guard can want to know the outcome of the same writes, and the writes are the
   * customer's cart either way - there is one set of them, not one per interested
   * party. It is set when a batch starts and cleared when that batch settles, so
   * its presence is the honest answer to "is a write in flight?".
   */
  private activeWriteBatch: Observable<boolean> | null = null;
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
  /** Where focus came from, so closing the dialog can hand it back. */
  private readonly previouslyFocused = capturePreviousFocus();

  readonly copy = CART_COPY;
  /** Exposed so a row renders its price in the same format as the total. */
  readonly formatAmount = formatAmount;

  /** True while a checkout press may not be repeated. */
  public get isCartBusy(): boolean {
    return this.isReconcilingCart || this.isCartWritePending;
  }

  /** The cart total in the storefront's money format, amount and currency together. */
  public get formattedTotal(): string {
    return formatMoney(this.totalPrice, CART_CURRENCY);
  }

  /**
   * What the checkout button says.
   *
   * <p>One label per state, so a disabled button always also explains why: "Sepeti
   * Onayla" on a button that will do nothing reads as a broken control, and the
   * two in-progress states are told apart so a customer waiting on a save can see
   * that it is the save they are waiting on.
   */
  public get checkoutLabel(): string {
    if (this.isCartWritePending) {
      return CART_COPY.saving;
    }
    if (this.isReconcilingCart) {
      return CART_COPY.reconciling;
    }
    if (this.items.length === 0) {
      return CART_COPY.empty;
    }
    return this.isCartConfirmed ? CART_COPY.checkout : CART_COPY.confirm;
  }

  /**
   * True while a batch of staged writes is in flight.
   *
   * <p>The one predicate every way out of the cart asks. The component's own
   * close and the router guard must agree on it, and they used to answer from two
   * different states - which is how a guard press could start a second batch over
   * rows an existing batch was already writing. The batch clears itself when it
   * settles, so a stale "pending" cannot survive a completed write.
   */
  private get hasWriteInFlight(): boolean {
    return this.activeWriteBatch !== null;
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

  /**
   * Escape closes the cart and Tab stays inside it.
   *
   * <p>While the payment dialog is open the cart ignores both. Escape belongs to
   * the dialog on top, whose own handler is the only one that knows whether a
   * close may cancel; the cart answering it as well would tear the dialog down
   * behind a charge that may be in flight, which is precisely what that dialog's
   * close path exists to prevent. Tab follows the same owner, so one key press
   * never drives two dialogs.
   */
  private readonly escHandler = (event: KeyboardEvent) => {
    if (this.isPaymentPhaseActive) {
      return;
    }
    if (event.key === 'Escape') {
      this.closeCartComponent();
      return;
    }
    if (event.key === 'Tab') {
      containTabKey(this.cartDialogRef?.nativeElement, event);
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
    // Focus lands on the dialog itself, so the name "Sepetim" is what a screen
    // reader reads out on arrival rather than whatever control happened to be
    // first in the tab order.
    focusDialog(this.cartDialogRef?.nativeElement);
    document.addEventListener('keydown', this.escHandler);
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    document.removeEventListener('keydown', this.escHandler);
    this.cartRequest?.unsubscribe();
    this.reconciliationRequest?.unsubscribe();
    this.cancelImageRequests();
    this.releaseProductImages();
    // After teardown, so focus is handed back to a page that is already there
    // rather than to a node this dialog is about to take with it. Skipped when
    // the element is gone - closing the cart with the browser Back button also
    // replaces the header button that opened it.
    restoreFocus(this.previouslyFocused);
    // Deliberately no attempt to flush the staged edits here. Teardown is not a
    // place a write can be *awaited* - by the time it runs the route is already
    // being left, so a request issued now reports its result to a component
    // nobody can see, and a request that had not been issued yet is exactly the
    // one that would never be sent. That is what lost edits in the first place.
    // The route's CanDeactivate guard asks while the cart is still on screen and
    // holds the navigation until the server has answered; the batches already in
    // flight are left to settle, which is why the guard shares them instead of
    // racing them.
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
   *
   * <p>The batch is shared, and it keeps its result. `forkJoin` over cold HTTP
   * requests would otherwise re-issue every write for each new subscriber, and a
   * second observer is a normal thing to have: the router guard waiting out a
   * write that is already in flight. Two batches over the same rows race each
   * other, and whichever loses is a change the customer believes was saved.
   */
  private persistStagedEdits(): Observable<boolean> {
    const edits = this.stagedEdits();
    this.isCartWritePending = edits.length > 0;
    if (edits.length === 0) {
      this.activeWriteBatch = null;
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

    const batch: Observable<boolean> = forkJoin(attempts).pipe(
      map((results) => {
        results
          .filter((result) => result.saved)
          .forEach((result) => this.discardStagedEdit(result.edit));
        const allSaved = results.every((result) => result.saved);
        // A router guard may have cancelled its subscription while this shared
        // batch stayed in flight. Report a refusal here so the still-open cart
        // shows it even when no guard or close callback remains to do so.
        if (!allSaved && !this.destroyed) {
          this.reportCartWriteFailure();
        }
        return allSaved;
      }),
      // Upstream of the share, so it runs once per batch even if the router
      // cancels its guard subscription. The write continues with refCount false;
      // its own completion must unlock the cart, since a cancelled guard will
      // never reach settleWriteBatch's result handler.
      finalize(() => {
        if (this.activeWriteBatch === batch) {
          this.activeWriteBatch = null;
          this.isCartWritePending = false;
        }
      }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    this.activeWriteBatch = batch;
    return batch;
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
    const request =
      edit.kind === 'remove'
        ? this.restService.removeProductFromUserCart(edit.productId)
        : this.restService.updateProductCountInUserCart(edit.productId, edit.count);

    return request.pipe(timeout({ each: CART_WRITE_TIMEOUT_MS }));
  }

  private discardStagedEdit(edit: StagedCartEdit): void {
    if (edit.kind === 'remove') {
      this.itemsToDelete.delete(edit.productId);
    } else {
      this.itemCountMap.delete(edit.productId);
    }
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
    this.cartMessage = CART_COPY.writeFailed;
  }

  /**
   * Settles the component's own state after a write batch did not save.
   *
   * <p>The pending close is withdrawn with the batch, and this is the whole fix:
   * the flag recorded a request to leave that was made *against* this batch, so
   * the batch's failure is what makes the request void. The batch carrying the
   * edits is the customer's cart, not one press's - a close is asked for from the
   * X button, the overlay, Escape and the route guard alike, and a second ask
   * while a batch is in flight is only ever recorded for the batch already
   * running. Leaving it set past a failure turned that request into a dismissal
   * the customer never got: the next press inherited it, and a press that had
   * nothing to do with the failed close took the cart away instead of
   * reconciling it.
   *
   * <p>The staged edits are deliberately untouched. They are what the customer
   * still sees, and the retry resends exactly them; only the departure is
   * withdrawn, never the change.
   */
  private settleFailedWriteBatch(): void {
    this.isCartWritePending = false;
    this.closeRequestedWhileWritePending = false;
    if (!this.destroyed) {
      this.reportCartWriteFailure();
    }
  }

  /**
   * Leaves the cart view.
   *
   * <p>Only ever reached once nothing is unsaved: either nothing was ever staged,
   * or every staged edit has been accepted by the server. Navigating earlier
   * would drop a quantity the customer set or resurrect a row they deleted,
   * because the cart they are looking at would no longer be the stored one.
   */
  private navigateAwayFromCart(): void {
    this.router.navigate([{ outlets: { modal: null } }], {
      relativeTo: this.route.parent ?? this.route,
    });
  }

  /**
   * Abandons a reconciliation that is still running, because the view it would
   * re-render is on its way out.
   *
   * <p>Only ever called once every staged write has succeeded. A reconciliation
   * cannot start before that, so there is nothing left for it to protect: by the
   * time one is in flight the staged edits have already landed.
   */
  private abandonReconciliationForClose(): void {
    this.reconciliationRequest?.unsubscribe();
    this.reconciliationRequest = null;
    this.isReconcilingCart = false;
  }

  /**
   * The single entry point for every way out of the cart view: the X button, a
   * click on the overlay, and the Escape key. They used to differ in what they
   * did to unsaved edits, which is how a close could lose one silently.
   *
   * <p>It never navigates while a staged write is in flight. Three situations,
   * and each one waits for the write that already carries the edits rather than
   * issuing a competing batch over the same rows:
   *
   * <ul>
   *   <li>A checkout press is already persisting edits. The close is recorded and
   *   the running batch settles it: on success the view goes, and deliberately
   *   without the reconciliation that press would have started, because the
   *   customer is no longer here to confirm anything. On failure the close is
   *   withdrawn, the cart stays open with the reason, and the refused edits stay
   *   staged - a customer whose change was refused must be able to see it and
   *   retry it, which is impossible once the view is gone.
   *   <li>Edits are staged but nothing is in flight. One awaitable batch is
   *   started here, and the view is left only if every edit lands.
   *   <li>Nothing is unsaved. The view goes immediately, and a reconciliation
   *   still running is abandoned: by the time one starts, every staged write has
   *   already succeeded, so there is nothing left for it to protect.
   * </ul>
   *
   * <p>The router guard answers the same question for the ways in that never
   * reach this method, and it shares both the batch and this reasoning rather than
   * duplicating either.
   */
  public closeCartComponent(): void {
    if (this.hasWriteInFlight) {
      this.closeRequestedWhileWritePending = true;
      return;
    }

    if (this.stagedEdits().length === 0) {
      this.abandonReconciliationForClose();
      this.navigateAwayFromCart();
      return;
    }

    this.persistStagedEdits().subscribe({
      next: (allSaved) => {
        if (!allSaved) {
          // The close this batch was carrying is void, and so is any close asked
          // for while it was in flight. Both are the batch's to settle.
          this.settleFailedWriteBatch();
          return;
        }
        this.isCartWritePending = false;
        if (this.destroyed) {
          return;
        }
        if (this.externalNavigationPending) {
          // A navigation the customer started elsewhere is waiting on this same
          // write. Navigating here would send them to the page behind the cart
          // and cancel the move they actually asked for, so the close is satisfied
          // by the write having landed and the router performs its own navigation.
          return;
        }
        this.navigateAwayFromCart();
      },
      error: () => {
        // persistStagedEdits reports a refusal as a result rather than throwing,
        // so this is a backstop. It fails closed, withdrawal included.
        this.settleFailedWriteBatch();
      },
    });
  }

  /**
   * Whether the router may leave this route, and only then.
   *
   * <p>Back, a forward navigation and a deep link all deactivate the cart route
   * without ever calling `closeCartComponent`, and teardown cannot save anything:
   * by the time `ngOnDestroy` runs the customer is already looking at the page
   * they navigated to, and a write issued from there reports its result to a
   * component nobody can see. So the route asks while the cart is still on
   * screen, and leaves only once the server has accepted every staged edit.
   *
   * <p>Three situations, and it never navigates itself - the router does that
   * once this answer is `true`:
   *
   * <ul>
   *   <li>Nothing staged and no write in flight. Allowed straight away: there is
   *   nothing to wait for, and a reconciliation still running is abandoned for
   *   the same reason `closeCartComponent` abandons it.
   *   <li>Edits staged, nothing in flight. One batch is started and this waits
   *   for it.
   *   <li>A write already in flight - a checkout press, or an X/Escape/overlay
   *   close that is already carrying these edits. That batch is waited out. It is
   *   the one batch: the writes are the customer's cart, and a second one would
   *   race the first over the same rows. The wait is recorded as an external
   *   navigation rather than as a close, so a checkout press that is carrying the
   *   edits settles the departure by answering `true` instead of starting a
   *   reconciliation nobody is there to confirm - and without navigating itself,
   *   because the destination belongs to the navigation the customer started.
   * </ul>
   *
   * <p>A refusal - or a write that never answers, which the same timeout reports
   * as a failure - answers `false`. Nothing navigates, the cart stays on screen
   * with the reason, and the refused edits stay staged, so a customer whose change
   * the server rejected can still see it and still retry it. That is the whole
   * reason the answer is a decision rather than a redirect: navigating and then
   * reporting a failure would leave them on the new page, unable to see or retry
   * the change that failed.
   */
  public canDeactivateCart(): Observable<boolean> {
    // Recorded before anything else, because the router has already decided where
    // it is going and this guard only answers whether it may go there. Every
    // write-completion callback has to know that the destination is not the
    // cart's to choose.
    this.externalNavigationPending = true;

    const pending = this.activeWriteBatch;
    if (pending) {
      return this.settleWriteBatch(pending);
    }

    if (this.stagedEdits().length === 0) {
      this.abandonReconciliationForClose();
      this.externalNavigationPending = false;
      return of(true);
    }

    return this.settleWriteBatch(this.persistStagedEdits());
  }

  /**
   * Turns the outcome of one write batch into the router's answer.
   *
   * <p>Subscribing to the shared batch rather than starting one is what keeps a
   * guard press from becoming a second write. `take(1)` because the router wants a
   * decision, not a subscription it has to clean up; the underlying batch is not
   * torn down by it.
   *
   * <p>The external-navigation state is cleared on every way out of the wait: a
   * decision (which the map makes before the router acts on it, so a following
   * close is not suppressed by a navigation that is already under way) and a
   * cancellation, which `finalize` covers. A guard that was dropped without ever
   * deciding - a navigation the router replaced, a superseded transition - must
   * not leave a phantom departure behind that would silence the next close.
   */
  private settleWriteBatch(batch: Observable<boolean>): Observable<boolean> {
    return batch.pipe(
      take(1),
      map((allSaved) => {
        this.isCartWritePending = false;
        this.closeRequestedWhileWritePending = false;
        this.externalNavigationPending = false;
        if (this.destroyed) {
          // There is nothing left to protect and nobody left to tell. Letting the
          // router proceed is the only outcome that does not strand the writes.
          return true;
        }
        if (!allSaved) {
          this.reportCartWriteFailure();
          return false;
        }
        this.abandonReconciliationForClose();
        return true;
      }),
      finalize(() => {
        this.externalNavigationPending = false;
      }),
    );
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
      alert(describeStockLimit(change.availableStock));
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
   *
   * <p>The batch also settles a close that was requested while it was running.
   * The writes carry the staged edits either way, so the only open question is
   * what the customer is still here for, and on success there is nothing left to
   * confirm - so the view is left without a reconciliation nobody can act on. On
   * failure the close is withdrawn, because a refused edit has to stay visible
   * and retryable.
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
        if (!allSaved) {
          // The close is withdrawn with the batch that was to carry it, so a
          // later press cannot inherit a dismissal this one never honoured.
          this.settleFailedWriteBatch();
          this.isReconcilingCart = false;
          return;
        }
        this.isCartWritePending = false;
        if (this.externalNavigationPending) {
          // The router is holding a navigation of the customer's own making on
          // this write, and it is going to leave the cart to wherever they asked
          // to go. Reconciling here would run for a customer who is no longer on
          // the page to confirm or abandon the result, and navigating would decide
          // a destination the navigation itself owns. So neither happens: the
          // write carried the staged edits, and the guard answers `true` so the
          // router can complete its own move.
          this.isReconcilingCart = false;
          return;
        }
        if (this.closeRequestedWhileWritePending) {
          this.closeRequestedWhileWritePending = false;
          this.isReconcilingCart = false;
          if (!this.destroyed) {
            this.navigateAwayFromCart();
          }
          return;
        }
        if (this.destroyed) {
          // The view is gone. The writes have landed, and reconciling now would
          // only mutate a destroyed component.
          this.isReconcilingCart = false;
          return;
        }
        this.requestReconciliation();
      },
      error: () => {
        // persistStagedEdits reports a refusal as a result rather than throwing,
        // so this is a backstop. It fails closed all the same.
        this.settleFailedWriteBatch();
        this.isReconcilingCart = false;
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
        this.cartMessage = CART_COPY.reconcileFailed;
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
      this.cartMessage = CART_COPY.changedDuringPress;
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
      this.cartMessage = CART_COPY.serverUpdated;
    }

    if (!repaired && !differs && this.items.length > 0) {
      // Nothing needed repairing and the response is the cart on screen, so the
      // confirmation the customer already gave stands. Checkout stays one press
      // away and never degrades into three.
      this.markCartConfirmed();
    }
  }

  /**
   * Records that the reconciliation the customer already asked for stands as
   * their confirmation.
   *
   * <p>Only the flag is set. The confirmed look of the checkout button is a
   * template binding rather than an inline style, because a hand-written
   * `backgroundColor = 'green'` is not the storefront's palette: it repainted the
   * one control a customer is about to press in a colour that appears nowhere
   * else in the product, and it did so by mutating the DOM outside Angular, which
   * is why it could not be undone by any state change.
   */
  private markCartConfirmed(): void {
    this.isCartConfirmed = true;
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
      alert(CART_COPY.insufficientStock);
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
      `${result.removedProductIds.length} ürün artık satışta olmadığı için sepetten çıkarıldı.`,
    );
  }
  if (result.reducedProductIds.length > 0) {
    parts.push(
      `${result.reducedProductIds.length} ürünün miktarı kalan stoğa göre azaltıldı.`,
    );
  }
  return `${parts.join(' ')} Lütfen sepeti kontrol edip onaylayın.`;
}
