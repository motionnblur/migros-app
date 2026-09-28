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

  it('stages a remove until confirmation and saves it on the first confirm step', () => {
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

    const removal = httpMock.expectOne((request) =>
      request.url.includes('/user/supply/removeProductFromUserCart'),
    );
    expect(removal.request.method).toBe('DELETE');
    expect(removal.request.params.get('productId')).toBe('11');
    removal.flush('');

    expect(component.isCartConfirmed).toBeTrue();
    expect(component.isPaymentPhaseActive).toBeFalse();
    expect(
      httpMock.match((request) => request.url.includes('/payment/checkouts')).length,
    ).toBe(0);
  });

  it('hands off to checkout only on the second confirmation without sending a client amount', () => {
    component.increaseProductCount(10);
    component.openPaymentComponent();

    const quantityUpdate = httpMock.expectOne((request) =>
      request.url.includes('/user/supply/updateProductCountInUserCart'),
    );
    expect(quantityUpdate.request.params.get('productId')).toBe('10');
    expect(quantityUpdate.request.params.get('count')).toBe('3');
    quantityUpdate.flush('');
    expect(component.isCartConfirmed).toBeTrue();
    expect(component.isPaymentPhaseActive).toBeFalse();

    component.openPaymentComponent();
    expect(component.isPaymentPhaseActive).toBeTrue();
    spyOn(PaymentComponent.prototype, 'loadStripe').and.resolveTo();
    fixture.detectChanges();

    const prepareCheckout = httpMock.expectOne((request) =>
      request.url.includes('/payment/checkouts'),
    );
    expect(prepareCheckout.request.method).toBe('POST');
    expect(prepareCheckout.request.body).toEqual({});
    prepareCheckout.flush({
      checkoutId: 'checkout-1',
      status: 'PREPARED',
      totalAmount: 60,
      amountMinor: 6000,
      currency: 'TRY',
    });
    httpMock
      .expectOne((request) =>
        request.url.includes('/user/supply/getProductData'),
      )
      .flush([]);
  });

  it('removes the final unit when decrementing and blocks empty-cart confirmation', () => {
    component.decreaseProductCount(11);

    expect(component.items.map((item) => item.productId)).toEqual([10]);
    expect(component.totalPrice).toBe(40);

    component.removeProductFromUserCart(10);
    component.openPaymentComponent();

    expect(component.items.length).toBe(0);
    expect(component.isPaymentPhaseActive).toBeFalse();
    expect(
      httpMock.match((request) =>
        request.url.includes('/user/supply/removeProductFromUserCart'),
      ).length,
    ).toBe(0);
  });
});
