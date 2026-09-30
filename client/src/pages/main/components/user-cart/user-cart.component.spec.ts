import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { IUserCartItemDto } from '../../../../interfaces/IUserCartItemDto';
import { PaymentComponent } from '../payment/payment.component';
import { UserCartComponent } from './user-cart.component';

describe('UserCartComponent', () => {
  let component: UserCartComponent;
  let fixture: ComponentFixture<UserCartComponent>;
  let httpMock: HttpTestingController;

  const cartItems: IUserCartItemDto[] = [
    {
      productId: 10,
      productName: 'Tam Süt',
      productPrice: 20,
      productCount: 2,
      availableStock: 4,
    },
    {
      productId: 11,
      productName: 'Yoğurt',
      productPrice: 15,
      productCount: 1,
      availableStock: 1,
    },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [UserCartComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    })
    .compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(UserCartComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock
      .expectOne((request) =>
        request.url.includes('/user/supply/getProductData'),
      )
      .flush(cartItems.map((item) => ({ ...item })));
    httpMock
      .match((request) => request.url.includes('/user/supply/getProductImage'))
      .forEach((request) => request.flush(new Blob(['image'])));
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    httpMock.match(() => true).forEach((request) => {
      if (!request.cancelled) {
        request.flush(request.request.responseType === 'blob' ? new Blob() : '');
      }
    });
    httpMock.verify({ ignoreCancelled: true });
  });

  it('should create', () => {
    expect(component).toBeTruthy();
    expect(component.totalPrice).toBe(55);
  });

  it('updates quantities immutably and enforces available stock', () => {
    const original = component.items[0];
    const alertSpy = spyOn(window, 'alert');

    component.increaseProductCount(10);

    expect(component.items[0].productCount).toBe(3);
    expect(component.items[0]).not.toBe(original);
    expect(original.productCount).toBe(2);
    expect(component.totalPrice).toBe(75);

    component.increaseProductCount(10);
    expect(component.items[0].productCount).toBe(4);
    expect(component.totalPrice).toBe(95);

    component.increaseProductCount(10);
    expect(alertSpy).toHaveBeenCalledWith(
      'Bu urunden en fazla 4 adet alabilirsiniz.',
    );
    expect(component.items[0].productCount).toBe(4);
    expect(component.totalPrice).toBe(95);

    component.decreaseProductCount(10);
    expect(component.items[0].productCount).toBe(3);
    expect(component.totalPrice).toBe(75);
  });

  it('stages a remove and persists it before the reconciling press reads the server', () => {
    component.removeProductFromUserCart(11);
    component.removeProductFromUserCart(11);

    expect(component.items.map((item) => item.productId)).toEqual([10]);
    expect(component.totalPrice).toBe(40);
    expect(
      httpMock.match((request) =>
        request.url.includes('/user/supply/removeProductFromUserCart'),
      ).length,
    ).toBe(0);

    component.openPaymentComponent();

    // The staged removal is written first and awaited. Reconciling before it
    // landed would answer for a cart that still held the removed product, and
    // the response would then re-render a row the customer just deleted.
    const removal = httpMock.expectOne((request) =>
      request.url.includes('/user/supply/removeProductFromUserCart'),
    );
    expect(removal.request.method).toBe('DELETE');
    expect(removal.request.params.get('productId')).toBe('11');
    expect(
      httpMock.match((request) => request.url.includes('/user/supply/reconcileCart'))
        .length,
    ).toBe(0);
    expect(component.isCartConfirmed).toBeFalse();
    expect(component.isPaymentPhaseActive).toBeFalse();
    removal.flush('');

    // Only now is the stored cart reconciled, and the confirmation the customer
    // already gave stands because the reconciled cart is the one on screen.
    const reconciliation = httpMock.expectOne((request) =>
      request.url.includes('/user/supply/reconcileCart'),
    );
    expect(reconciliation.request.method).toBe('POST');
    expect(component.isPaymentPhaseActive).toBeFalse();
    reconciliation.flush({
      cart: [{ ...cartItems[0] }],
      removedProductIds: [],
      reducedProductIds: [],
    });

    expect(component.isCartConfirmed).toBeTrue();
    expect(component.isPaymentPhaseActive).toBeFalse();
    expect(
      httpMock.match((request) => request.url.includes('/payment/checkouts')).length,
    ).toBe(0);
  });

  it('hands off to checkout only on the second confirmation without sending a client amount', () => {
    component.increaseProductCount(10);

    // The first press persists the staged quantity and only then reconciles.
    // Nothing needed repair here, so the confirmation the customer already gave
    // stands and the reconciled cart is the one they are looking at.
    component.openPaymentComponent();
    const quantityUpdate = httpMock.expectOne((request) =>
      request.url.includes('/user/supply/updateProductCountInUserCart'),
    );
    expect(quantityUpdate.request.params.get('productId')).toBe('10');
    expect(quantityUpdate.request.params.get('count')).toBe('3');
    expect(component.isPaymentPhaseActive).toBeFalse();
    expect(
      httpMock.match((request) => request.url.includes('/user/supply/reconcileCart'))
        .length,
    ).toBe(0);
    quantityUpdate.flush('');

    const reconciliation = httpMock.expectOne((request) =>
      request.url.includes('/user/supply/reconcileCart'),
    );
    expect(reconciliation.request.method).toBe('POST');
    expect(component.isPaymentPhaseActive).toBeFalse();
    reconciliation.flush({
      cart: [
        { ...cartItems[0], productCount: 3 },
        { ...cartItems[1] },
      ],
      removedProductIds: [],
      reducedProductIds: [],
    });

    expect(component.isCartConfirmed).toBeTrue();
    expect(component.isPaymentPhaseActive).toBeFalse();
    expect(component.totalPrice).toBe(75);

    component.openPaymentComponent();
    expect(component.isPaymentPhaseActive).toBeTrue();
    spyOn(PaymentComponent.prototype, 'loadStripe').and.resolveTo();
    fixture.detectChanges();

    const prepareCheckout = httpMock.expectOne((request) =>
      request.url.includes('/payment/checkouts'),
    );
    expect(prepareCheckout.request.method).toBe('POST');
    // The client never supplies an amount: the server computes the snapshot it
    // reserves from and returns it.
    expect(prepareCheckout.request.body).toEqual({});
    prepareCheckout.flush({
      checkoutId: 'checkout-1',
      status: 'PREPARED',
      totalAmount: 75,
      amountMinor: 7500,
      currency: 'TRY',
    });
    httpMock
      .expectOne((request) =>
        request.url.includes('/user/supply/getProductData'),
      )
      .flush([]);
  });

  it('persists a staged removal from an empty view instead of stranding it', () => {
    component.decreaseProductCount(11);

    expect(component.items.map((item) => item.productId)).toEqual([10]);
    expect(component.totalPrice).toBe(40);

    component.removeProductFromUserCart(10);
    expect(component.items.length).toBe(0);
    component.openPaymentComponent();

    // The rows are gone from the view but not from the cart, so the press that
    // cannot be made from the disabled buy button still has to reach the server
    // before anything is reconciled or offered.
    const removals = httpMock.match((request) =>
      request.url.includes('/user/supply/removeProductFromUserCart'),
    );
    expect(removals.length).toBe(2);
    expect(
      httpMock.match((request) => request.url.includes('/user/supply/reconcileCart'))
        .length,
    ).toBe(0);
    removals.forEach((request) => request.flush(''));

    httpMock
      .expectOne((request) => request.url.includes('/user/supply/reconcileCart'))
      .flush({ cart: [], removedProductIds: [], reducedProductIds: [] });

    expect(component.items.length).toBe(0);
    expect(component.isCartConfirmed).toBeFalse();
    expect(component.isPaymentPhaseActive).toBeFalse();
    expect(
      httpMock.match((request) => request.url.includes('/payment/checkouts')).length,
    ).toBe(0);
  });
});
