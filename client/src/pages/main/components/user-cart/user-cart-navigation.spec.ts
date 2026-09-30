import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Location } from '@angular/common';
import { provideLocationMocks } from '@angular/common/testing';
import { Route, Router, RouterOutlet, provideRouter } from '@angular/router';

import { routes } from '../../../../app/app.routes';
import { IUserCartItemDto } from '../../../../interfaces/IUserCartItemDto';
import { UserCartComponent } from './user-cart.component';

/**
 * Leaving the cart route through the router with a staged edit unsaved.
 *
 * <p>The defect this pins: the cart lives on the named `modal` outlet, so the
 * browser Back button deactivates its route without ever calling
 * `closeCartComponent`. Nothing asked the component, so the staged quantity was
 * discarded by teardown - and the only fallback, a flush in `ngOnDestroy`, could
 * not have helped either: a request issued after the route is already being left
 * reports its result to a component nobody can see, and one that had not been
 * issued yet was never sent. Either way the customer watched 3 revert to 2.
 *
 * <p>Driven through a real router and a real history, because the thing under test
 * is the router's own leave path. Calling `closeCartComponent()` or the guard
 * method directly would have passed against the old code, which had no guard on
 * the route at all.
 *
 * <p>The cart route is taken from the shipped route table rather than restated
 * here, so a guard that exists only in its own unit test - and was never wired
 * into the application - fails these.
 */
describe('leaving the cart route with a staged edit', () => {
  let fixture: ComponentFixture<TestHostComponent>;
  let httpMock: HttpTestingController;
  let router: Router;
  let location: Location;

  const isCartReadRequest = (request: { url: string }): boolean =>
    request.url.includes('/user/supply/getProductData');
  const isImageRequest = (request: { url: string }): boolean =>
    request.url.includes('/user/supply/getProductImage');
  const isCountUpdateRequest = (request: { url: string }): boolean =>
    request.url.includes('/user/supply/updateProductCountInUserCart');
  const isRemovalRequest = (request: { url: string }): boolean =>
    request.url.includes('/user/supply/removeProductFromUserCart');
  const isWriteRequest = (request: { url: string }): boolean =>
    isCountUpdateRequest(request) || isRemovalRequest(request);
  const isReconcileRequest = (request: { url: string }): boolean =>
    request.url.includes('/user/supply/reconcileCart');

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

  @Component({
    standalone: true,
    imports: [RouterOutlet],
    template: '<router-outlet></router-outlet><router-outlet name="modal"></router-outlet>',
  })
  class TestHostComponent {}

  /** The shipped cart route, complete with the guard that leaves nothing staged behind. */
  const cartRoute = routes
    .flatMap((route) => route.children ?? [])
    .find((route) => route.path === 'cart') as Route;

  /**
   * That same route mounted under a stand-in shell.
   *
   * <p>The real parent is the main page, which pulls in the header, the footer and
   * the auth and support session reads. None of them is what leaves the cart
   * route, and all of them would only add requests this suite has to answer
   * without making the guard any more exercised.
   */
  const testRoutes: Route[] = [
    {
      path: '',
      component: TestHostComponent,
      children: [
        { path: '', pathMatch: 'full', component: TestHostComponent },
        cartRoute,
      ],
    },
  ];

  function cart(): UserCartComponent {
    return fixture.debugElement.query(By.directive(UserCartComponent))
      .componentInstance as UserCartComponent;
  }

  /** The "+" on the first cart row, reached by click rather than by method. */
  function increaseButton(): HTMLButtonElement {
    return fixture.nativeElement.querySelectorAll(
      '.cart-item .input-group .btn-orange',
    )[1] as HTMLButtonElement;
  }

  function cartDialog(): HTMLElement | null {
    return fixture.nativeElement.querySelector('#cart-container');
  }

  function isOnCartRoute(): boolean {
    return cartDialog() !== null;
  }

  function saveError(): HTMLElement | null {
    return fixture.nativeElement.querySelector(
      '[data-testid="cart-reconciliation-message"]',
    );
  }

  function flushImages(): void {
    httpMock
      .match(isImageRequest)
      .forEach((request) => request.flush(new Blob(['image'])));
  }

  /**
   * Mounts the shell, lets the router perform its initial navigation, and opens
   * the cart over it.
   *
   * <p>`initialNavigation` is called by hand because nothing bootstraps the
   * application in a `TestBed` fixture, and it is what subscribes the router to
   * the browser's Back button. Without it the Back press is a no-op the test
   * cannot tell from a guard that is not working.
   */
  function openCart(): UserCartComponent {
    fixture = TestBed.createComponent(TestHostComponent);
    fixture.detectChanges();
    router.initialNavigation();
    tick();
    fixture.detectChanges();

    router.navigateByUrl('/(modal:cart)');
    tick();
    fixture.detectChanges();
    httpMock
      .expectOne(isCartReadRequest)
      .flush([{ ...available }, { ...scarce }]);
    flushImages();
    fixture.detectChanges();
    return cart();
  }

  /** The browser Back button, and as long as the router needs to react. */
  function pressBack(): void {
    location.back();
    tick();
    fixture.detectChanges();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TestHostComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(testRoutes),
        // A Back button under test. Without this the router drives the browser's
        // real history, and a synthetic Back press does nothing a test can see -
        // which is exactly the navigation under test.
        provideLocationMocks(),
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    location = TestBed.inject(Location);
  });

  afterEach(() => {
    fixture?.destroy();
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

  it('holds the Back navigation until the staged write has landed, then leaves', fakeAsync(() => {
    const component = openCart();

    expect(cartDialog()).toBeTruthy();
    increaseButton().click();
    fixture.detectChanges();
    // The customer sees 3; the server still holds 2.
    expect(component.items[0].productCount).toBe(3);
    expect(component.totalPrice).toBe(75);

    pressBack();

    // The router asked the component, and the component answered by starting the
    // write. Nothing has navigated yet: leaving here is the silent loss, because
    // the staged copy of 3 exists only in a component about to be destroyed.
    const [update] = httpMock.match(isCountUpdateRequest);
    expect(update.request.params.get('productId')).toBe('10');
    expect(update.request.params.get('count')).toBe('3');
    expect(isOnCartRoute()).toBeTrue();
    expect(component.isCartWritePending).toBeTrue();
    // Reconciling or reserving here would be answered for the 2 the customer did
    // not choose.
    expect(httpMock.match(isReconcileRequest).length).toBe(0);
    expect(
      httpMock.match((request) => request.url.includes('/payment/checkouts'))
        .length,
    ).toBe(0);

    update.flush('');
    tick();
    fixture.detectChanges();

    // Only once the server has accepted it does the router complete the move.
    expect(isOnCartRoute()).toBeFalse();
    expect(router.url).toBe('/');
    expect(component.isCartWritePending).toBeFalse();
  }));

  it('cancels the Back navigation and keeps the refused quantity on screen for a retry', fakeAsync(() => {
    const component = openCart();

    increaseButton().click();
    fixture.detectChanges();
    pressBack();

    const [update] = httpMock.match(isCountUpdateRequest);
    expect(update.request.params.get('count')).toBe('3');
    update.flush('Cart update refused', { status: 409, statusText: 'Conflict' });
    tick();
    fixture.detectChanges();

    // The customer is not taken to the previous page to be told afterwards that
    // their change was refused: an edit they can no longer see is an edit they
    // cannot retry. The cart stays, with the reason, and the edit stays staged.
    expect(isOnCartRoute()).toBeTrue();
    expect(router.url).toBe('/(modal:cart)');
    expect(component.items[0].productCount).toBe(3);
    expect(component.cartMessage).toContain('kaydedilemedi');
    expect(component.isCartWritePending).toBeFalse();
    const message = saveError();
    expect(message).toBeTruthy();
    expect(message!.textContent).toContain('kaydedilemedi');
    expect(httpMock.match(isReconcileRequest).length).toBe(0);

    // A second attempt to leave resends exactly the edit that was refused, and
    // only then leaves. Both backend writers are idempotent, so that retry
    // converges rather than doubling. It is issued as a plain navigation because
    // the history double has already spent its one Back entry on the cancelled
    // press, exactly as a real browser would let the customer simply try again.
    router.navigateByUrl('/');
    tick();
    fixture.detectChanges();
    const retry = httpMock.expectOne(isCountUpdateRequest);
    expect(retry.request.params.get('count')).toBe('3');
    retry.flush('');
    tick();
    fixture.detectChanges();

    expect(isOnCartRoute()).toBeFalse();
    expect(router.url).toBe('/');
  }));

  it('waits out a write that is already in flight instead of sending a second one', fakeAsync(() => {
    const component = openCart();

    increaseButton().click();
    fixture.detectChanges();
    // A checkout press is already carrying the staged edit.
    component.openPaymentComponent();
    const [update] = httpMock.match(isCountUpdateRequest);
    expect(update).toBeTruthy();
    expect(component.isCartWritePending).toBeTrue();

    pressBack();

    // The decisive assertion. A second batch would race the first over the same
    // row, and whichever lost is a change the customer believes was saved.
    expect(httpMock.match(isWriteRequest).length).toBe(0);
    expect(isOnCartRoute()).toBeTrue();
    expect(httpMock.match(isReconcileRequest).length).toBe(0);
    expect(
      httpMock.match((request) => request.url.includes('/payment/checkouts'))
        .length,
    ).toBe(0);

    update.flush('');
    tick();
    fixture.detectChanges();

    // The one write carried the edit, so the router completes the move - and
    // without the reconciliation the press would have started, since there is
    // nobody left to confirm it.
    expect(isOnCartRoute()).toBeFalse();
    expect(router.url).toBe('/');
    expect(httpMock.match(isWriteRequest).length).toBe(0);
    expect(httpMock.match(isReconcileRequest).length).toBe(0);
  }));

  it('cancels the Back navigation when the in-flight write is refused', fakeAsync(() => {
    const component = openCart();

    increaseButton().click();
    fixture.detectChanges();
    component.openPaymentComponent();
    const [update] = httpMock.match(isCountUpdateRequest);
    expect(update).toBeTruthy();
    pressBack();

    // Still only the press's own write: waiting for it must not add another.
    expect(httpMock.match(isWriteRequest).length).toBe(0);

    update.flush('Cart update refused', { status: 409, statusText: 'Conflict' });
    tick();
    fixture.detectChanges();

    expect(isOnCartRoute()).toBeTrue();
    expect(component.items[0].productCount).toBe(3);
    expect(component.cartMessage).toContain('kaydedilemedi');
    expect(httpMock.match(isReconcileRequest).length).toBe(0);
  }));

  it('leaves immediately when there is nothing staged', fakeAsync(() => {
    openCart();

    pressBack();

    // Nothing unsaved, so there is nothing to wait for and nothing to report.
    expect(isOnCartRoute()).toBeFalse();
    expect(router.url).toBe('/');
    expect(httpMock.match(isWriteRequest).length).toBe(0);
  }));

  it('cancels the Back navigation when a staged write never answers', fakeAsync(() => {
    const component = openCart();

    increaseButton().click();
    fixture.detectChanges();
    pressBack();
    expect(httpMock.match(isCountUpdateRequest).length).toBe(1);

    // The request hangs. Unbounded, the cart would be pinned in its saving state
    // with the navigation in limbo and no way to tell "still saving" from "lost".
    // The same bound the close path uses turns it into a reported failure.
    tick(20_000);
    fixture.detectChanges();

    expect(isOnCartRoute()).toBeTrue();
    expect(component.cartMessage).toContain('kaydedilemedi');
    expect(component.isCartWritePending).toBeFalse();
  }));
});
