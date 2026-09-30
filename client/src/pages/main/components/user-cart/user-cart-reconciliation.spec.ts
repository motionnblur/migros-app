import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

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

  it('persists staged quantity edits once an unrepaired reconciliation confirms the cart', () => {
    component.increaseProductCount(10);
    component.openPaymentComponent();

    reconcileWith({
      cart: [
        { ...available, productCount: 3 },
        { ...scarce },
      ],
      removedProductIds: [],
      reducedProductIds: [],
    });

    const update = httpMock.expectOne((request) =>
      request.url.includes('/user/supply/updateProductCountInUserCart'),
    );
    expect(update.request.params.get('productId')).toBe('10');
    expect(update.request.params.get('count')).toBe('3');
    update.flush('');

    expect(component.isCartConfirmed).toBeTrue();
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
   * emptied. Refusing to reconcile leaves that residue stored forever, because
   * an empty view can never trigger the repair and checkout is unreachable from
   * an empty cart. Reconciling is the only way to tell the two apart, so the
   * empty view reconciles and the server's report says whether anything was
   * actually there.
   */
  it('reconciles an empty view, because an empty cart and a fully unbuyable one are indistinguishable', () => {
    component.items = [];
    component.totalPrice = 0;
    fixture.detectChanges();

    component.openPaymentComponent();

    httpMock.expectOne(isReconcileRequest).flush({
      cart: [],
      removedProductIds: [10, 11],
      reducedProductIds: [],
    });
    fixture.detectChanges();

    // The residue is cleared, the customer is told, and no checkout is offered.
    expect(component.items).toEqual([]);
    expect(component.isCartConfirmed).toBeFalse();
    expect(component.isPaymentPhaseActive).toBeFalse();
    expect(
      fixture.nativeElement.querySelector('[data-testid="cart-reconciliation-message"]'),
    ).toBeTruthy();
  });

  it('reconciles an empty view without confirming when the stored cart was genuinely empty', () => {
    component.items = [];
    component.totalPrice = 0;
    fixture.detectChanges();

    component.openPaymentComponent();

    httpMock.expectOne(isReconcileRequest).flush({
      cart: [],
      removedProductIds: [],
      reducedProductIds: [],
    });
    fixture.detectChanges();

    // Nothing to remove and nothing to reserve, so there is nothing to confirm.
    expect(component.isCartConfirmed).toBeFalse();
    expect(component.isPaymentPhaseActive).toBeFalse();
    expect(
      fixture.nativeElement.querySelector('[data-testid="cart-reconciliation-message"]'),
    ).toBeNull();
  });
});
