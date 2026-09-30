import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  flush,
  tick,
} from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { ICartReconciliation } from '../../../../interfaces/ICartReconciliation';
import { IUserCartItemDto } from '../../../../interfaces/IUserCartItemDto';
import { PaymentComponent } from '../payment/payment.component';
import { UserCartComponent } from './user-cart.component';

/**
 * The first press of checkout reconciles the stored cart before it proceeds.
 *
 * <p>The defect this pins: `getProductData` is a pure read and deliberately hides
 * entries it cannot render, while checkout reserves from the stored list. A cart
 * holding a product that was deleted or sold out therefore looked complete in the
 * dialog, then failed at checkout for a line the customer was never shown - and
 * since there was no row for it, there was no way to remove it either. The
 * reconciliation closes that gap from the committed state the reservation will
 * actually use.
 *
 * <p>It runs *before* the customer's confirmation, never instead of it. Anything
 * the server had to repair is re-rendered and has to be confirmed again, so no
 * quantity is ever charged for that the customer did not see and approve.
 *
 * <p>Driven through the real HTTP stack rather than a stubbed service: what is
 * under test is the sequence of requests a customer triggers, and a spy object
 * could not show whether a checkout request was issued alongside the reconcile.
 */
describe('UserCartComponent checkout reconciliation', () => {
  let component: UserCartComponent;
  let fixture: ComponentFixture<UserCartComponent>;
  let httpMock: HttpTestingController;

  const available: IUserCartItemDto = {
    productId: 10,
    productName: 'Tam Sut',
    productPrice: 20,
    productCount: 2,
    availableStock: 4,
  };
  const scarce: IUserCartItemDto = {
    productId: 11,
    productName: 'Yogurt',
    productPrice: 15,
    productCount: 1,
    availableStock: 1,
  };

  const cartItems: IUserCartItemDto[] = [{ ...available }, { ...scarce }];

  const isReconcileRequest = (request: { url: string }): boolean =>
    request.url.includes('/user/supply/reconcileCart');
  const isCheckoutRequest = (request: { url: string }): boolean =>
    request.url.includes('/payment/checkouts');
  const isImageRequest = (request: { url: string }): boolean =>
    request.url.includes('/user/supply/getProductImage');
  const isCartReadRequest = (request: { url: string }): boolean =>
    request.url.includes('/user/supply/getProductData');
  const isCountUpdateRequest = (request: { url: string }): boolean =>
    request.url.includes('/user/supply/updateProductCountInUserCart');
  const isRemovalRequest = (request: { url: string }): boolean =>
    request.url.includes('/user/supply/removeProductFromUserCart');
  /** Either of the two cart writes a locally staged edit is persisted with. */
  const isWriteRequest = (request: { url: string }): boolean =>
    isCountUpdateRequest(request) || isRemovalRequest(request);

  /** The buy button in the cart footer, which is disabled for an empty cart. */
  function buyButton(): HTMLButtonElement {
    return fixture.nativeElement.querySelector(
      '#cart-container .card-footer button',
    );
  }

  /** The action the empty view offers in place of the disabled buy button. */
  function emptyCartAction(): HTMLButtonElement {
    return fixture.nativeElement.querySelector(
      '[data-testid="reconcile-empty-cart"]',
    );
  }

  /** Flushes the per-item image reads the component issues for a cart. */
  function flushImages(): void {
    httpMock
      .match(isImageRequest)
      .forEach((request) => request.flush(new Blob(['image'])));
  }

  /** Answers a reconciliation with the given report. */
  function reconcileWith(result: ICartReconciliation): void {
    httpMock.expectOne(isReconcileRequest).flush(result);
    flushImages();
    fixture.detectChanges();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [UserCartComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(UserCartComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock
      .expectOne(isCartReadRequest)
      .flush(cartItems.map((entry) => ({ ...entry })));
    flushImages();
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    // Whatever the component is still waiting on is answered rather than left
    // open, so `verify` below reports only genuinely unhandled requests.
    httpMock.match(() => true).forEach((request) => {
      if (request.cancelled) {
        return;
      }
      if (isImageRequest(request.request)) {
        request.flush(new Blob());
      } else if (isReconcileRequest(request.request)) {
        request.flush({ cart: [], removedProductIds: [], reducedProductIds: [] });
      } else {
        request.flush('');
      }
    });
    httpMock.verify({ ignoreCancelled: true });
  });

  it('reconciles the stored cart on the first press instead of opening checkout', () => {
    expect(component.items.length).toBe(2);
    expect(component.totalPrice).toBe(55);
    expect(component.isCartConfirmed).toBeFalse();

    component.openPaymentComponent();

    const request = httpMock.expectOne(isReconcileRequest);
    expect(request.request.method).toBe('POST');

    // The decisive assertion: a single reconcile is issued and nothing else.
    // Proceeding straight to checkout here is the defect - the reservation
    // would fail on a line the customer cannot see or remove.
    httpMock.expectNone(isCheckoutRequest);
    expect(component.isPaymentPhaseActive).toBeFalse();
    expect(component.isReconcilingCart).toBeTrue();
    // Still nothing confirmed and nothing reserved, either.
    expect(component.isCartConfirmed).toBeFalse();
  });

  it('disables the buy button while a reconciliation is in flight', () => {
    component.openPaymentComponent();

    fixture.detectChanges();
    const button: HTMLButtonElement = fixture.nativeElement.querySelector(
      '#cart-container .card-footer button',
    );

    // Otherwise a second press would fire a second reconciliation against a
    // cart the customer has not yet seen the result of.
    expect(component.isReconcilingCart).toBeTrue();
    expect(button.disabled).toBeTrue();
  });

  it('surfaces a removed product and requires the customer to confirm the new cart', () => {
    component.openPaymentComponent();
    reconcileWith({
      cart: [{ ...available }],
      removedProductIds: [11],
      reducedProductIds: [],
    });

    expect(component.items.map((entry) => entry.productId)).toEqual([10]);
    // Recomputed from what survived, not left at the pre-reconcile figure.
    expect(component.totalPrice).toBe(40);
    expect(component.cartMessage).toContain('1');
    expect(component.cartMessage).toContain('cikarildi');
    expect(component.isReconcilingCart).toBeFalse();
    // Still unconfirmed: the customer has just been shown a different cart from
    // the one they pressed confirm on.
    expect(component.isCartConfirmed).toBeFalse();
    expect(component.isPaymentPhaseActive).toBeFalse();

    const message = fixture.nativeElement.querySelector(
      '[data-testid="cart-reconciliation-message"]',
    );
    expect(message).toBeTruthy();
    expect(message.textContent).toContain('cikarildi');

    // Second press: the customer approves the repaired cart. It is reconciled
    // again rather than trusted from the client's own memory, and this time
    // there is nothing left to repair, so the approval stands.
    component.openPaymentComponent();
    expect(component.isPaymentPhaseActive).toBeFalse();
    reconcileWith({
      cart: [{ ...available }],
      removedProductIds: [],
      reducedProductIds: [],
    });
    expect(component.isCartConfirmed).toBeTrue();
    expect(component.isPaymentPhaseActive).toBeFalse();

    // Checkout opens only from the confirmation of the cart the customer was
    // shown. Nothing was reserved on the unreconciled list.
    component.openPaymentComponent();
    expect(component.isPaymentPhaseActive).toBeTrue();
    expect(component.totalPrice).toBe(40);
    httpMock.expectNone(isReconcileRequest);
  });

  it('surfaces a reduced quantity and requires a fresh confirmation for it', () => {
    component.openPaymentComponent();
    // Reduced is not removed: the product stays in the cart at the quantity
    // that is actually buyable, and the message says so instead of pretending
    // the line was deleted.
    reconcileWith({
      cart: [{ ...available }, { ...scarce }],
      removedProductIds: [],
      reducedProductIds: [11],
    });

    expect(component.items.map((entry) => entry.productId)).toEqual([10, 11]);
    expect(component.items[1].productCount).toBe(1);
    expect(component.totalPrice).toBe(55);
    expect(component.cartMessage).toContain('azaltildi');
    expect(component.cartMessage).not.toContain('cikarildi');
    expect(component.isCartConfirmed).toBeFalse();

    component.openPaymentComponent();
    reconcileWith({
      cart: [{ ...available }, { ...scarce }],
      removedProductIds: [],
      reducedProductIds: [],
    });

    expect(component.isCartConfirmed).toBeTrue();
    expect(component.isPaymentPhaseActive).toBeFalse();

    component.openPaymentComponent();
    expect(component.isPaymentPhaseActive).toBeTrue();
  });

  it('names both outcomes separately when a removal and a reduction happen together', () => {
    component.openPaymentComponent();

    reconcileWith({
      cart: [{ ...available, productCount: 1 }],
      removedProductIds: [11],
      reducedProductIds: [10],
    });

    expect(component.totalPrice).toBe(20);
    expect(component.cartMessage).toContain('cikarildi');
    expect(component.cartMessage).toContain('azaltildi');
    expect(component.isCartConfirmed).toBeFalse();
  });

  it('keeps the confirmation the customer already gave when nothing needed repair', () => {
    component.openPaymentComponent();

    reconcileWith({
      cart: cartItems.map((entry) => ({ ...entry })),
      removedProductIds: [],
      reducedProductIds: [],
    });

    // The normal path: an extra reconcile request, but not an extra
    // confirmation. The customer's original press still counts, so checkout
    // stays two presses away and never degrades into three.
    expect(component.isCartConfirmed).toBeTrue();
    expect(component.cartMessage).toBe('');
    expect(component.items.length).toBe(2);
    expect(component.totalPrice).toBe(55);
    expect(httpMock.match(isReconcileRequest).length).toBe(0);
    expect(component.isPaymentPhaseActive).toBeFalse();

    component.openPaymentComponent();

    expect(component.isPaymentPhaseActive).toBeTrue();

    // The payment phase really does reserve, on the second press and not the
    // first, from the cart the customer confirmed.
    spyOn(PaymentComponent.prototype, 'loadStripe').and.resolveTo();
    fixture.detectChanges();
    const prepareCheckout = httpMock.expectOne(isCheckoutRequest);
    expect(prepareCheckout.request.method).toBe('POST');
    prepareCheckout.flush({
      checkoutId: 'checkout-1',
      status: 'PREPARED',
      totalAmount: 55,
      amountMinor: 5500,
      currency: 'TRY',
    });
    // Preparing the checkout emptied the stored cart server-side, so the live
    // view is re-read rather than left showing reserved items.
    httpMock.expectOne(isCartReadRequest).flush([]);
  });

  /**
   * The defect these pin: the confirmation path used to *subscribe* to the
   * staged cart writes and carry straight on, so the reconciliation answered for
   * a cart the server had not accepted yet. The response was then adopted as the
   * displayed cart and the customer confirmed it, while the write it raced went
   * on to change the stored cart afterwards. From that point the cart on screen
   * and the cart that checkout reserves from were two different carts, and the
   * quantity charged for was neither of the ones anyone had approved.
   *
   * <p>Every response below is the server's own view of the stored cart. None of
   * them contains a local edit that had not been saved, because a mocked
   * reconciliation that echoed the unsaved edits would make the race disappear
   * from the test rather than from the flow.
   */
  it('saves a locally staged quantity before the reconciliation reads the server cart', () => {
    component.increaseProductCount(10);

    // The customer sees 3; the server still holds 2.
    expect(component.items[0].productCount).toBe(3);
    expect(component.totalPrice).toBe(75);

    component.openPaymentComponent();

    // The write is in flight and nothing else has been sent. A reconciliation
    // issued now would be answering for the 2 the customer did not choose.
    const [update] = httpMock.match(isCountUpdateRequest);
    expect(update.request.params.get('productId')).toBe('10');
    expect(update.request.params.get('count')).toBe('3');
    expect(httpMock.match(isReconcileRequest).length).toBe(0);
    expect(httpMock.match(isCheckoutRequest).length).toBe(0);
    expect(component.isCartWritePending).toBeTrue();
    expect(component.isReconcilingCart).toBeTrue();
    expect(component.isCartConfirmed).toBeFalse();
    expect(component.isPaymentPhaseActive).toBeFalse();

    update.flush('');

    // Only once the write has landed is the stored cart reconciled, and the
    // response is the cart that write produced.
    httpMock.expectOne(isReconcileRequest).flush({
      cart: [{ ...available, productCount: 3 }, { ...scarce }],
      removedProductIds: [],
      reducedProductIds: [],
    });
    flushImages();
    fixture.detectChanges();

    expect(component.items[0].productCount).toBe(3);
    expect(component.totalPrice).toBe(75);
    expect(component.isCartConfirmed).toBeTrue();
    // Nothing is left queued: the edit is stored, not merely requested. A
    // second write would resurface the cart to a state it has already left.
    expect(httpMock.match(isWriteRequest).length).toBe(0);
  });

  it('blocks confirmation and reservation while a staged write is still in flight', () => {
    component.increaseProductCount(10);
    component.openPaymentComponent();
    const [update] = httpMock.match(isCountUpdateRequest);
    expect(update).toBeTruthy();

    // The button is disabled for the whole press, write included.
    fixture.detectChanges();
    expect(buyButton().disabled).toBeTrue();
    buyButton().click();

    // A press that reaches the handler anyway - a queued click, a keyboard
    // activation, a fast double press - changes nothing: no second write, no
    // reconciliation, and above all no checkout against a cart whose writes have
    // not landed.
    component.openPaymentComponent();
    component.openPaymentComponent();

    expect(httpMock.match(isWriteRequest).length).toBe(0);
    expect(httpMock.match(isReconcileRequest).length).toBe(0);
    expect(httpMock.match(isCheckoutRequest).length).toBe(0);
    expect(component.isCartConfirmed).toBeFalse();
    expect(component.isPaymentPhaseActive).toBeFalse();

    // The press that was already running still completes, and only then does the
    // cart become confirmable.
    update.flush('');
    httpMock.expectOne(isReconcileRequest).flush({
      cart: [{ ...available, productCount: 3 }, { ...scarce }],
      removedProductIds: [],
      reducedProductIds: [],
    });
    flushImages();
    fixture.detectChanges();

    expect(component.isCartConfirmed).toBeTrue();
    expect(component.isPaymentPhaseActive).toBeFalse();
    expect(buyButton().disabled).toBeFalse();
  });

  it('keeps a refused write staged, blocks checkout, and retries the same edit', () => {
    component.increaseProductCount(10);
    component.openPaymentComponent();

    httpMock
      .expectOne(isCountUpdateRequest)
      .flush('You cannot add more than available stock.', {
        status: 400,
        statusText: 'Bad Request',
      });
    fixture.detectChanges();

    // Failing closed: nothing is confirmed, nothing is reconciled, and the
    // customer is told why rather than left believing the change was saved.
    expect(component.isCartConfirmed).toBeFalse();
    expect(component.isReconcilingCart).toBeFalse();
    expect(component.isCartWritePending).toBeFalse();
    expect(component.cartMessage).toContain('kaydedilemedi');
    expect(httpMock.match(isReconcileRequest).length).toBe(0);
    expect(httpMock.match(isCheckoutRequest).length).toBe(0);
    // The edit is still on screen and still unsaved, so it must still be staged.
    expect(component.items[0].productCount).toBe(3);

    // The retry re-sends exactly what was refused, then reconciles.
    component.openPaymentComponent();
    const retry = httpMock.expectOne(isCountUpdateRequest);
    expect(retry.request.params.get('count')).toBe('3');
    retry.flush('');

    httpMock.expectOne(isReconcileRequest).flush({
      cart: [{ ...available, productCount: 3 }, { ...scarce }],
      removedProductIds: [],
      reducedProductIds: [],
    });
    flushImages();
    fixture.detectChanges();

    expect(component.isCartConfirmed).toBeTrue();
    expect(component.cartMessage).toBe('');
  });

  it('does not let a staged removal reappear in the confirmed cart', () => {
    component.removeProductFromUserCart(11);
    expect(component.items.map((entry) => entry.productId)).toEqual([10]);

    component.openPaymentComponent();

    // The removal is written first. Reconciling while it is in flight returns
    // the row the customer just deleted, and that response is what gets
    // confirmed.
    const removal = httpMock.expectOne(isRemovalRequest);
    expect(removal.request.params.get('productId')).toBe('11');
    expect(httpMock.match(isReconcileRequest).length).toBe(0);
    removal.flush('');

    // The server answers for the cart the removal left behind.
    httpMock.expectOne(isReconcileRequest).flush({
      cart: [{ ...available }],
      removedProductIds: [],
      reducedProductIds: [],
    });
    flushImages();
    fixture.detectChanges();

    expect(component.items.map((entry) => entry.productId)).toEqual([10]);
    expect(component.isCartConfirmed).toBeTrue();

    // And that is what checkout reserves from: no lingering write, no second
    // reconciliation, no re-issued removal.
    component.openPaymentComponent();
    expect(component.isPaymentPhaseActive).toBeTrue();
    expect(httpMock.match(isReconcileRequest).length).toBe(0);
    expect(httpMock.match(isRemovalRequest).length).toBe(0);
  });

  it('asks again when the reconciled cart differs for a reason the report does not name', () => {
    component.increaseProductCount(10);
    component.openPaymentComponent();
    httpMock.expectOne(isCountUpdateRequest).flush('');

    // The saved quantity is not what the server will sell, but the report names
    // neither a removal nor a reduction. Empty id lists are not proof that the
    // displayed and stored carts match - only the cart itself is.
    httpMock.expectOne(isReconcileRequest).flush({
      cart: [{ ...available, productCount: 2 }, { ...scarce }],
      removedProductIds: [],
      reducedProductIds: [],
    });
    flushImages();
    fixture.detectChanges();

    expect(component.items[0].productCount).toBe(2);
    expect(component.totalPrice).toBe(55);
    expect(component.cartMessage).toContain('guncellendi');
    expect(component.isCartConfirmed).toBeFalse();
    expect(component.isPaymentPhaseActive).toBeFalse();
  });

  it('invalidates a confirmation as soon as the customer edits the cart again', () => {
    component.openPaymentComponent();
    reconcileWith({
      cart: cartItems.map((entry) => ({ ...entry })),
      removedProductIds: [],
      reducedProductIds: [],
    });
    expect(component.isCartConfirmed).toBeTrue();

    // The approved cart is no longer the cart on screen, and the new quantity
    // has not reached the server, so checkout from here would reserve the old
    // one behind the customer's back.
    component.increaseProductCount(10);
    expect(component.isCartConfirmed).toBeFalse();

    component.openPaymentComponent();
    expect(component.isPaymentPhaseActive).toBeFalse();
    expect(httpMock.expectOne(isCountUpdateRequest).request.params.get('count')).toBe(
      '3',
    );
  });

  /**
   * An edit that arrives while a press is running.
   *
   * <p>The rows are disabled for the duration of a press, but the edit can still
   * come from anywhere the template does not gate - a queued click, a restored
   * form, a replayed event - and the response it races is one the server computed
   * before the edit existed. Adopting it would put a deleted row back and drop a
   * quantity the customer had just set, while the staged copy of that edit stayed
   * behind to be written later: the mirror image of the original race, reached
   * from the fixed path.
   */
  it('does not overwrite an edit made while the reconciliation is in flight', () => {
    component.openPaymentComponent();
    const [reconcile] = httpMock.match(isReconcileRequest);
    expect(reconcile).toBeTruthy();

    component.removeProductFromUserCart(11);
    expect(component.items.map((entry) => entry.productId)).toEqual([10]);

    // The response predates the removal and still contains the deleted row.
    reconcile.flush({
      cart: cartItems.map((entry) => ({ ...entry })),
      removedProductIds: [],
      reducedProductIds: [],
    });
    fixture.detectChanges();

    // The newer edit survives, nothing is confirmed, and the customer is not told
    // the server changed anything - it did not.
    expect(component.items.map((entry) => entry.productId)).toEqual([10]);
    expect(component.isCartConfirmed).toBeFalse();
    expect(component.isPaymentPhaseActive).toBeFalse();
    expect(component.cartMessage).toContain('degisti');
    expect(component.cartMessage).not.toContain('sunucu');

    // The edit is still unsaved, so the next press writes it and only then
    // reconciles a cart that includes it.
    component.openPaymentComponent();
    const [removal] = httpMock.match(isRemovalRequest);
    expect(removal.request.params.get('productId')).toBe('11');
    expect(httpMock.match(isReconcileRequest).length).toBe(0);
    removal.flush('');

    httpMock.expectOne(isReconcileRequest).flush({
      cart: [{ ...available }],
      removedProductIds: [],
      reducedProductIds: [],
    });
    flushImages();
    fixture.detectChanges();

    expect(component.isCartConfirmed).toBeTrue();
  });

  it('locks the cart rows for the whole press and while the payment phase is open', () => {
    const trashButtons = (): HTMLButtonElement[] =>
      Array.from(
        fixture.nativeElement.querySelectorAll('.cart-item .btn-outline-danger'),
      );
    const decreaseButtons = (): HTMLButtonElement[] =>
      Array.from<HTMLButtonElement>(
        fixture.nativeElement.querySelectorAll(
          '.cart-item .input-group .btn-orange',
        ),
      ).filter((_, index) => index % 2 === 0);

    fixture.detectChanges();
    expect(trashButtons().some((button) => button.disabled)).toBeFalse();
    expect(decreaseButtons().some((button) => button.disabled)).toBeFalse();

    component.openPaymentComponent();
    fixture.detectChanges();

    // An editable row during a press is how a removal or a quantity ends up
    // answered by a response the server produced before it existed.
    expect(trashButtons().every((button) => button.disabled)).toBeTrue();
    expect(decreaseButtons().every((button) => button.disabled)).toBeTrue();

    httpMock.expectOne(isReconcileRequest).flush({
      cart: cartItems.map((entry) => ({ ...entry })),
      removedProductIds: [],
      reducedProductIds: [],
    });
    flushImages();
    fixture.detectChanges();

    expect(component.isCartConfirmed).toBeTrue();
    expect(trashButtons().some((button) => button.disabled)).toBeFalse();

    // And the rows stay locked while the payment phase is open: the reservation
    // has already taken the stored cart, so an edit now would be written back
    // over a snapshot that is about to be charged for.
    component.openPaymentComponent();
    expect(component.isPaymentPhaseActive).toBeTrue();
    spyOn(PaymentComponent.prototype, 'loadStripe').and.resolveTo();
    fixture.detectChanges();

    expect(trashButtons().every((button) => button.disabled)).toBeTrue();
    expect(decreaseButtons().every((button) => button.disabled)).toBeTrue();

    httpMock.expectOne(isCheckoutRequest).flush({
      checkoutId: 'checkout-1',
      status: 'PREPARED',
      totalAmount: 55,
      amountMinor: 5500,
      currency: 'TRY',
    });
    httpMock
      .expectOne(isCartReadRequest)
      .flush(cartItems.map((entry) => ({ ...entry })));
    flushImages();
    fixture.detectChanges();

    expect(trashButtons().every((button) => button.disabled)).toBeTrue();
  });

  it('leaves the cart untouched and open for retry when reconciliation fails', () => {
    component.openPaymentComponent();
    httpMock
      .expectOne(isReconcileRequest)
      .flush('Cart could not be reconciled', {
        status: 409,
        statusText: 'Conflict',
      });
    fixture.detectChanges();

    expect(component.items.length).toBe(2);
    expect(component.totalPrice).toBe(55);
    expect(component.isCartConfirmed).toBeFalse();
    expect(component.isReconcilingCart).toBeFalse();
    // A failed reconciliation is not a failed cart. Reporting it as a lost item
    // would be a lie about their cart; continuing silently would reserve from a
    // list nobody reconciled. It is neither: the customer retries deliberately.
    expect(component.cartMessage).toContain('dogrulanamadi');
    expect(component.isPaymentPhaseActive).toBeFalse();
    expect(
      fixture.nativeElement.querySelector(
        '[data-testid="cart-reconciliation-message"]',
      ),
    ).toBeTruthy();

    // A deliberate retry is a fresh reconcile, not a fall-through to checkout.
    component.openPaymentComponent();
    expect(httpMock.expectOne(isReconcileRequest)).toBeTruthy();
    expect(component.isPaymentPhaseActive).toBeFalse();
  });

  it('keeps a cart row whose product has no image, since deleted products have none', () => {
    // A cart read can return a product whose image was removed with it. The row
    // renders from the placeholder, so a 404 here must not escape the component
    // as an unhandled error and must not drop the row.
    component.handleCheckoutPrepared();
    httpMock
      .expectOne(isCartReadRequest)
      .flush(cartItems.map((entry) => ({ ...entry })));

    expect(() => {
      httpMock.match(isImageRequest).forEach((request) =>
        // A blob read cannot be answered with a string body, so the 404 is
        // delivered as an empty Blob - which is exactly how the real 404 reaches
        // the subscriber.
        request.flush(new Blob(), { status: 404, statusText: 'Not Found' }),
      );
      fixture.detectChanges();
    }).not.toThrow();

    expect(component.items.map((entry) => entry.productId)).toEqual([10, 11]);
    expect(component.totalPrice).toBe(55);
    expect(fixture.nativeElement.querySelectorAll('.cart-item').length).toBe(2);
    expect(
      fixture.nativeElement.querySelector('img').getAttribute('src'),
    ).toBe('assets/placeholder.png');
  });

  /**
   * An empty view is not necessarily an empty cart.
   *
   * <p>The stored cart can hold only products that have since been deleted or
   * sold out, in which case the pure read renders nothing and the two are
   * indistinguishable to the customer - the cart looks exactly like one they
   * emptied. Checkout is unreachable from an empty cart, so the residue needs its
   * own reachable action: reconciling is the only way to tell the two apart, and
   * the server's report says whether anything was actually there.
   *
   * <p>Driven through a DOM click rather than the component method, because the
   * point of the fix is that the action is reachable *from the view*. Calling
   * `openPaymentComponent()` directly would have passed against the old code,
   * where the button it lives on was disabled and nothing a customer could press
   * got there.
   */
  it('reconciles an empty view from the action the customer can actually click', () => {
    // Every stored entry became unbuyable: the read renders nothing at all.
    component.items = [];
    component.totalPrice = 0;
    fixture.detectChanges();

    // The buy button is disabled with nothing to buy, and clicking it is inert.
    expect(buyButton().disabled).toBeTrue();
    buyButton().click();
    expect(httpMock.match(isReconcileRequest).length).toBe(0);
    expect(component.isPaymentPhaseActive).toBeFalse();

    const action = emptyCartAction();
    expect(action).toBeTruthy();
    action.click();

    httpMock.expectOne(isReconcileRequest).flush({
      cart: [],
      removedProductIds: [10, 11],
      reducedProductIds: [],
    });
    fixture.detectChanges();

    // The residue is cleared, the customer is told, and no checkout is offered.
    expect(component.items).toEqual([]);
    expect(component.cartMessage).toContain('cikarildi');
    expect(component.isCartConfirmed).toBeFalse();
    expect(component.isPaymentPhaseActive).toBeFalse();
    expect(
      fixture.nativeElement.querySelector('[data-testid="cart-reconciliation-message"]'),
    ).toBeTruthy();
  });

  it('persists a removal made from the cart before the empty view reconciles', () => {
    // The customer empties the view themselves. The rows are gone from the
    // display but not from the cart, and reconciling first would read a server
    // cart that still holds both products.
    component.removeProductFromUserCart(10);
    component.removeProductFromUserCart(11);
    fixture.detectChanges();

    expect(component.items).toEqual([]);
    emptyCartAction().click();

    const removals = httpMock.match(isRemovalRequest);
    expect(removals.length).toBe(2);
    expect(httpMock.match(isReconcileRequest).length).toBe(0);
    removals.forEach((request) => request.flush(''));

    httpMock.expectOne(isReconcileRequest).flush({
      cart: [],
      removedProductIds: [],
      reducedProductIds: [],
    });
    fixture.detectChanges();

    // The removals were saved, so the reconciliation finds nothing to repair and
    // the customer is not told about a cleanup they already did.
    expect(component.cartMessage).toBe('');
    expect(component.isCartConfirmed).toBeFalse();
    expect(component.isPaymentPhaseActive).toBeFalse();
  });

  it('cannot enter the payment phase from a genuinely empty cart', () => {
    component.items = [];
    component.totalPrice = 0;
    fixture.detectChanges();

    emptyCartAction().click();

    httpMock.expectOne(isReconcileRequest).flush({
      cart: [],
      removedProductIds: [],
      reducedProductIds: [],
    });
    fixture.detectChanges();

    // Nothing to remove and nothing to reserve, so there is nothing to confirm.
    expect(component.isCartConfirmed).toBeFalse();
    expect(buyButton().disabled).toBeTrue();
    buyButton().click();
    expect(component.isPaymentPhaseActive).toBeFalse();
    expect(httpMock.match(isCheckoutRequest).length).toBe(0);
    expect(
      fixture.nativeElement.querySelector('[data-testid="cart-reconciliation-message"]'),
    ).toBeNull();
  });
});

/**
 * Closing the cart view with edits that have not reached the server yet.
 *
 * <p>The defect this pins: closing navigated away immediately, and the only
 * attempt to save was a fire-and-forget flush in `ngOnDestroy`. A write that had
 * not been issued when the view was torn down was simply never sent, and one
 * that was in flight had its result reported to a component nobody could see.
 * Either way the customer watched a quantity change or a deletion evaporate.
 *
 * <p>Every way out now goes through one method, and it leaves only once the
 * server has accepted every staged edit. A refusal keeps the cart open with the
 * reason and the edit staged, because an edit the customer cannot see is an edit
 * they cannot retry.
 */
describe('UserCartComponent close with staged edits', () => {
  let component: UserCartComponent;
  let fixture: ComponentFixture<UserCartComponent>;
  let httpMock: HttpTestingController;
  let navigate: jasmine.Spy;

  const isWriteRequest = (request: { url: string }): boolean =>
    request.url.includes('/user/supply/updateProductCountInUserCart') ||
    request.url.includes('/user/supply/removeProductFromUserCart');
  const isCountUpdateRequest = (request: { url: string }): boolean =>
    request.url.includes('/user/supply/updateProductCountInUserCart');
  const isRemovalRequest = (request: { url: string }): boolean =>
    request.url.includes('/user/supply/removeProductFromUserCart');
  const isReconcileRequest = (request: { url: string }): boolean =>
    request.url.includes('/user/supply/reconcileCart');
  const isImageRequest = (request: { url: string }): boolean =>
    request.url.includes('/user/supply/getProductImage');

  /** The X in the cart header. */
  function closeButton(): HTMLButtonElement {
    return fixture.nativeElement.querySelector('#cart-container .btn-close');
  }

  /** The backdrop behind the dialog. */
  function overlay(): HTMLElement {
    return fixture.nativeElement.querySelector('#cart-overlay');
  }

  function pressEscape(): void {
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
  }

  /** Answers a reconciliation with the cart currently on screen, so nothing is repaired. */
  function flushReconciliationAsUnchanged(): void {
    httpMock.match(isReconcileRequest).forEach((request) =>
      request.flush({
        cart: component.items.map((entry) => ({ ...entry })),
        removedProductIds: [],
        reducedProductIds: [],
      }),
    );
  }

  function flushImages(): void {
    httpMock
      .match(isImageRequest)
      .forEach((request) => request.flush(new Blob(['image'])));
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [UserCartComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    // Spied rather than allowed to run: this suite is about whether the close
    // happens at all, and a real outlet-less navigation would only add noise.
    navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

    fixture = TestBed.createComponent(UserCartComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock
      .expectOne((request) => request.url.includes('/user/supply/getProductData'))
      .flush([
        {
          productId: 10,
          productName: 'Tam Sut',
          productPrice: 20,
          productCount: 2,
          availableStock: 4,
        },
        {
          productId: 11,
          productName: 'Yogurt',
          productPrice: 15,
          productCount: 1,
          availableStock: 4,
        },
      ]);
    flushImages();
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    httpMock.match(() => true).forEach((request) => {
      if (request.cancelled) {
        return;
      }
      if (isImageRequest(request.request)) {
        request.flush(new Blob());
      } else if (isReconcileRequest(request.request)) {
        request.flush({ cart: [], removedProductIds: [], reducedProductIds: [] });
      } else {
        request.flush('');
      }
    });
    httpMock.verify({ ignoreCancelled: true });
  });

  it('waits for a staged write to land before the X button leaves the view', () => {
    component.increaseProductCount(10);
    closeButton().click();

    // The write is issued, and the customer is still looking at the cart they
    // just edited. Leaving now is the silent loss: the edit exists only in this
    // component, and it is about to be destroyed.
    const [update] = httpMock.match(isCountUpdateRequest);
    expect(update.request.params.get('count')).toBe('3');
    expect(navigate).not.toHaveBeenCalled();
    expect(component.isCartWritePending).toBeTrue();

    update.flush('');

    // Only once the server has accepted it does the view go.
    expect(navigate).toHaveBeenCalledTimes(1);
    expect(navigate).toHaveBeenCalledWith(
      [{ outlets: { modal: null } }],
      jasmine.anything(),
    );
    expect(component.isCartWritePending).toBeFalse();
  });

  it('keeps the cart open and retryable when an overlay close cannot be saved', () => {
    component.increaseProductCount(10);
    overlay().click();

    httpMock
      .expectOne(isCountUpdateRequest)
      .flush('Cart update refused', { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    // The customer does not lose the edit to a dismissal they did not ask to be
    // final: the cart stays, the reason is shown, and the change is still there.
    expect(navigate).not.toHaveBeenCalled();
    expect(component.cartMessage).toContain('kaydedilemedi');
    expect(component.isCartWritePending).toBeFalse();
    expect(component.items[0].productCount).toBe(3);
    expect(fixture.nativeElement.querySelector('#cart-container')).toBeTruthy();

    const message = fixture.nativeElement.querySelector(
      '[data-testid="cart-reconciliation-message"]',
    );
    expect(message).toBeTruthy();
    expect(message.textContent).toContain('kaydedilemedi');

    // A second attempt resends exactly the edit that failed, and succeeds.
    overlay().click();
    const retry = httpMock.expectOne(isCountUpdateRequest);
    expect(retry.request.params.get('count')).toBe('3');
    retry.flush('');
    expect(navigate).toHaveBeenCalledTimes(1);
  });

  it('defers an Escape during a checkout press to the batch already in flight', () => {
    component.increaseProductCount(10);
    component.openPaymentComponent();
    const [update] = httpMock.match(isCountUpdateRequest);
    expect(update).toBeTruthy();

    pressEscape();

    // No second batch: two batches over the same row race each other, and
    // whichever loses is an edit the customer believes was saved.
    expect(httpMock.match(isWriteRequest).length).toBe(0);
    expect(navigate).not.toHaveBeenCalled();

    update.flush('');

    // The write succeeded, so the close happens - and without the reconciliation
    // that press would have started, since there is nobody left to confirm it.
    expect(navigate).toHaveBeenCalledTimes(1);
    expect(httpMock.match(isReconcileRequest).length).toBe(0);
    expect(component.isReconcilingCart).toBeFalse();
  });

  it('withdraws the deferred close when the checkout batch fails', () => {
    component.increaseProductCount(10);
    component.openPaymentComponent();
    const [update] = httpMock.match(isCountUpdateRequest);
    pressEscape();

    update.flush('Cart update refused', { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    // The dismissal is cancelled rather than honoured over a refused change: the
    // quantity the customer set has to stay on screen and stay staged.
    expect(navigate).not.toHaveBeenCalled();
    expect(component.cartMessage).toContain('kaydedilemedi');
    expect(component.isReconcilingCart).toBeFalse();
    expect(component.isCartWritePending).toBeFalse();
    expect(httpMock.match(isReconcileRequest).length).toBe(0);
    expect(component.items[0].productCount).toBe(3);

    // And it is still the customer's to retry - now through checkout, which is
    // no longer waiting on a close that was withdrawn.
    component.openPaymentComponent();
    const retry = httpMock.expectOne(isCountUpdateRequest);
    expect(retry.request.params.get('count')).toBe('3');
    retry.flush('');
    // Nothing needed repairing, so the confirmation the customer already gave
    // stands - the withdrawn close did not turn into an extra press.
    flushReconciliationAsUnchanged();
    fixture.detectChanges();
    expect(navigate).not.toHaveBeenCalled();
    expect(component.isCartConfirmed).toBeTrue();
  });

  it('resends only the edit that failed when a two-edit close is refused', () => {
    component.increaseProductCount(10);
    component.removeProductFromUserCart(11);
    closeButton().click();

    // Selected by intent rather than by position: the batch holds a count rewrite
    // and a removal, and the point of the test is which of the two survives.
    const [count] = httpMock.match(isCountUpdateRequest);
    const [removal] = httpMock.match(isRemovalRequest);
    expect(count).toBeTruthy();
    expect(removal).toBeTruthy();

    // One lands, one is refused. The batch fails as a whole, so the cart stays -
    // but only the refused edit is still unsaved, and only that one is resent.
    count.flush('');
    removal.flush('Removal refused', { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect(navigate).not.toHaveBeenCalled();
    expect(component.cartMessage).toContain('kaydedilemedi');

    closeButton().click();
    const retried = httpMock.match(isWriteRequest);
    expect(retried.length).toBe(1);
    expect(isRemovalRequest(retried[0].request)).toBeTrue();
    expect(retried[0].request.params.get('productId')).toBe('11');

    retried[0].flush('');
    expect(navigate).toHaveBeenCalledTimes(1);
  });

  it('treats a write that never answers as a failed one and frees the close', fakeAsync(() => {
      expect(true).toBeTrue();
      component.increaseProductCount(10);
      closeButton().click();

      const [update] = httpMock.match(isCountUpdateRequest);
      expect(update).toBeTruthy();

      // The request hangs. Without a bound the cart is pinned in its saving
      // state for good: rows locked, checkout unreachable, and no way to tell
      // "still saving" from "lost".
      tick(20_000);

      // Reported as a refusal, so the customer is let out of the trap and told
      // what happened. The request is abandoned rather than left in flight.
      expect(component.isCartWritePending).toBeFalse();
      expect(component.cartMessage).toContain('kaydedilemedi');
      expect(navigate).not.toHaveBeenCalled();

      // Retrying is safe precisely because the outcome is unknown and both
      // writers are idempotent, so a duplicate converges instead of doubling.
      closeButton().click();
      const retry = httpMock.match(isCountUpdateRequest);
      expect(retry.length).toBe(1);
      expect(retry[0].request.params.get('count')).toBe('3');
      retry[0].flush('');

      expect(navigate).toHaveBeenCalledTimes(1);
      flush();
  }));

  it('leaves immediately when nothing is staged and a reconciliation is running', () => {
    component.openPaymentComponent();
    const [reconcile] = httpMock.match(isReconcileRequest);
    expect(reconcile).toBeTruthy();

    closeButton().click();

    // A reconciliation only ever starts once every staged write has succeeded,
    // so there is nothing left for it to protect and its response would only
    // re-render a view on its way out.
    expect(navigate).toHaveBeenCalledTimes(1);
    expect(reconcile.cancelled).toBeTrue();
    expect(component.isReconcilingCart).toBeFalse();
  });

  it('leaves immediately when nothing has been edited at all', () => {
    closeButton().click();

    // Nothing unsaved, so there is nothing to wait for and nothing to report.
    expect(navigate).toHaveBeenCalledTimes(1);
    expect(httpMock.match(isWriteRequest).length).toBe(0);
  });
});

