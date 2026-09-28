import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { PaymentApiService } from './payment-api.service';

describe('PaymentApiService API contracts', () => {
  let service: PaymentApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(PaymentApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('prepares checkout with POST and an empty object body', () => {
    service.prepareCheckout().subscribe();
    const request = httpMock.expectOne('/payment/checkouts');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({});
    request.flush({ checkoutId: 'checkout-1' });
  });

  it('reads checkout and payment status with their existing GET URLs', () => {
    service.getCheckoutStatus('checkout-1').subscribe();
    service.getPaymentStatus('checkout-1').subscribe();
    const checkout = httpMock.expectOne('/payment/checkouts/checkout-1');
    const payment = httpMock.expectOne('/payment/checkouts/checkout-1/status');
    expect(checkout.request.method).toBe('GET');
    expect(payment.request.method).toBe('GET');
    checkout.flush({ checkoutId: 'checkout-1' });
    payment.flush({ checkoutId: 'checkout-1' });
  });

  it('charges with POST and the token body, then cancels with an empty object body', () => {
    service.chargeCheckout('checkout-1', 'tok_test').subscribe();
    service.cancelCheckout('checkout-1').subscribe();
    const charge = httpMock.expectOne('/payment/checkouts/checkout-1/charge');
    const cancel = httpMock.expectOne('/payment/checkouts/checkout-1/cancel');
    expect(charge.request.method).toBe('POST');
    expect(charge.request.body).toEqual({ token: 'tok_test' });
    expect(cancel.request.method).toBe('POST');
    expect(cancel.request.body).toEqual({});
    charge.flush({ checkoutId: 'checkout-1', success: true });
    cancel.flush({ checkoutId: 'checkout-1', status: 'CANCELLED' });
  });
});
