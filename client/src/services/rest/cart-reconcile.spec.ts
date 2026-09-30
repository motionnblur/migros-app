import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { CartOrdersApiService } from './cart-orders-api.service';
import { RestService } from './rest.service';
import { ICartReconciliation } from '../../interfaces/ICartReconciliation';
import { IUserCartItemDto } from '../../interfaces/IUserCartItemDto';

const RECONCILE_URL = '/user/supply/reconcileCart';

function item(overrides: Partial<IUserCartItemDto> = {}): IUserCartItemDto {
  return {
    productId: 10,
    productName: 'Tam Sut',
    productPrice: 20,
    productCount: 2,
    availableStock: 4,
    ...overrides,
  };
}

/**
 * The reconciliation request is the wire contract the cart fix depends on.
 *
 * <p>It is a POST and not a GET because it changes stored state under the same
 * row lock every other cart writer takes, and a GET would be a cacheable,
 * retryable, prefetchable description of a mutation. It also has to report
 * *what* it changed, separately for removals and reductions: the client cannot
 * tell those apart on its own, and a customer whose order was quietly altered
 * has to be told which of the two happened.
 */
describe('CartOrdersApiService.reconcileUserCart', () => {
  let service: CartOrdersApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CartOrdersApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('reconciles with POST against exactly the reconcile endpoint', () => {
    service.reconcileUserCart().subscribe();

    const request = httpMock.expectOne(RECONCILE_URL);

    expect(request.request.method).toBe('POST');
    // No body: the cart being reconciled is the caller's own, identified by the
    // session. Passing product ids from the client would reintroduce exactly the
    // client-side view of the cart this call exists to replace.
    expect(request.request.body).toBeNull();
    request.flush({ cart: [], removedProductIds: [], reducedProductIds: [] });
  });

  it('never issues the reconciliation as a GET', () => {
    let method: string | undefined;
    service.reconcileUserCart().subscribe();
    const request = httpMock.expectOne(RECONCILE_URL);
    method = request.request.method;
    request.flush({
      cart: [],
      removedProductIds: [],
      reducedProductIds: [],
    });

    // A reconciliation changes stored state, so it must not be a GET. Asserted
    // on the captured request rather than through expectNone, which passes
    // vacuously when no GET was ever made.
    expect(method).toBe('POST');
  });

  it('carries the reconciled cart and both report lists through unchanged', () => {
    let received: ICartReconciliation | undefined;
    service.reconcileUserCart().subscribe((result) => (received = result));

    const payload: ICartReconciliation = {
      cart: [item({ productId: 11, productName: 'Yogurt', productCount: 1, availableStock: 1 })],
      removedProductIds: [10],
      reducedProductIds: [12],
    };
    httpMock.expectOne(RECONCILE_URL).flush(payload);

    expect(received).toEqual(payload);
    expect(received?.cart.map((entry) => entry.productId)).toEqual([11]);
    expect(received?.removedProductIds).toEqual([10]);
    expect(received?.reducedProductIds).toEqual([12]);
  });

  it('reports an empty reconciliation distinctly from a repaired one', () => {
    let received: ICartReconciliation | undefined;
    service.reconcileUserCart().subscribe((result) => (received = result));

    httpMock
      .expectOne(RECONCILE_URL)
      .flush({ cart: [item()], removedProductIds: [], reducedProductIds: [] });

    // Empty lists are the only signal that the customer's original
    // confirmation still applies; deriving that from "cart is non-empty" would
    // make a repaired cart look unrepaired.
    expect(received?.removedProductIds).toEqual([]);
    expect(received?.reducedProductIds).toEqual([]);
    expect(received?.cart.length).toBe(1);
  });

  it('surfaces a server failure instead of reporting an empty reconciliation', () => {
    let errorStatus = 0;
    service.reconcileUserCart().subscribe({
      next: () => fail('a failed reconciliation must not emit a result'),
      error: (error) => (errorStatus = error.status),
    });

    httpMock
      .expectOne(RECONCILE_URL)
      .flush('Cart could not be reconciled', {
        status: 409,
        statusText: 'Conflict',
      });

    // Silently reporting "nothing changed" here is what would let a customer
    // walk into a checkout that is guaranteed to fail.
    expect(errorStatus).toBe(409);
  });
});

/**
 * `RestService` is the compatibility facade the cart component actually injects,
 * so the reconciliation has to be reachable through it. Asserted over the real
 * HTTP stack rather than against a spy, because the thing worth pinning is that
 * the facade does not open a second, divergent path to the same endpoint.
 */
describe('RestService.reconcileUserCart delegation', () => {
  let restService: RestService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    restService = TestBed.inject(RestService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('delegates to the cart domain client, reaching one reconcile request', () => {
    let received: ICartReconciliation | undefined;
    restService.reconcileUserCart().subscribe((result) => (received = result));

    const request = httpMock.expectOne(RECONCILE_URL);

    expect(request.request.method).toBe('POST');
    request.flush({
      cart: [item()],
      removedProductIds: [],
      reducedProductIds: [],
    });

    expect(received?.cart.map((entry) => entry.productId)).toEqual([10]);
    // Exactly one request: the facade must not re-read the cart as well.
    httpMock.expectNone(() => true);
  });
});
