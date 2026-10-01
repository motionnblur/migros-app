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

import { CART_COPY, UserCartComponent } from './user-cart.component';
import { PaymentComponent } from '../payment/payment.component';
import { IUserCartItemDto } from '../../../../interfaces/IUserCartItemDto';

/**
 * How the cart reads and behaves as a dialog.
 *
 * <p>The cart's *flow* - the staged save before the reconciliation, the report
 * that has to be seen, the empty view that still needs a reachable action - is
 * pinned in `user-cart-reconciliation.spec.ts` and `user-cart-navigation.spec.ts`.
 * This suite covers the presentation and the interaction contract: the Turkish
 * wording, the formatting of an amount, the announced states, and the keyboard
 * and focus behaviour of a dialog that is a hand-rolled pair of fixed divs rather
 * than something the framework makes modal for it.
 *
 * <p>Presses are real clicks and focus movements are real ones. A disabled button
 * plus a direct call to the handler is how the old empty-cart gap stayed
 * invisible, so anything here about what a customer can *do* goes through the
 * DOM.
 */
describe('UserCartComponent presentation', () => {
  let fixture: ComponentFixture<UserCartComponent>;
  let component: UserCartComponent;
  let httpMock: HttpTestingController;

  const available: IUserCartItemDto = {
    productId: 10,
    productName: 'Tam Süt',
    productPrice: 20.5,
    productCount: 2,
    availableStock: 4,
  };
  const scarce: IUserCartItemDto = {
    productId: 11,
    productName: 'Yoğurt',
    productPrice: 15,
    productCount: 1,
    availableStock: 1,
  };
  const cartItems: IUserCartItemDto[] = [{ ...available }, { ...scarce }];

  const isCartRead = (request: { url: string }): boolean =>
    request.url.includes('/user/supply/getProductData');
  const isImage = (request: { url: string }): boolean =>
    request.url.includes('/user/supply/getProductImage');
  const isReconcile = (request: { url: string }): boolean =>
    request.url.includes('/user/supply/reconcileCart');
  const isCountUpdate = (request: { url: string }): boolean =>
    request.url.includes('/user/supply/updateProductCountInUserCart');
  const isCheckout = (request: { url: string }): boolean =>
    request.url.includes('/payment/checkouts');

  function query<T extends HTMLElement>(testId: string): T {
    return fixture.nativeElement.querySelector(`[data-testid="${testId}"]`) as T;
  }

  function flushImages(): void {
    httpMock.match(isImage).forEach((request) => request.flush(new Blob(['image'])));
  }

  function pressKey(key: string, shiftKey = false): void {
    document.dispatchEvent(
      new KeyboardEvent('keydown', { key, shiftKey, bubbles: true }),
    );
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

    spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(UserCartComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock.expectOne(isCartRead).flush(cartItems.map((item) => ({ ...item })));
    flushImages();
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    httpMock.match(() => true).forEach((request) => {
      if (request.cancelled) {
        return;
      }
      if (isImage(request.request)) {
        request.flush(new Blob());
      } else if (isReconcile(request.request)) {
        request.flush({ cart: [], removedProductIds: [], reducedProductIds: [] });
      } else {
        request.flush('');
      }
    });
    httpMock.verify({ ignoreCancelled: true });
  });

  describe('Turkish copy', () => {
    it('titles the dialog and names its close control', () => {
      expect(query('cart-dialog').textContent).toContain('Sepetim');
      expect(query<HTMLButtonElement>('cart-close').getAttribute('aria-label')).toBe(
        CART_COPY.close,
      );
    });

    it('corrects the diacritics of the empty view', () => {
      component.items = [];
      component.totalPrice = 0;
      fixture.detectChanges();

      const empty = query('cart-dialog').textContent ?? '';

      expect(empty).toContain('Sepetiniz henüz boş.');
      expect(empty).toContain('Satışta olmayan ürünler');
      expect(empty).toContain('kontrol ederek temizleyebilirsiniz');
      expect(query<HTMLButtonElement>('reconcile-empty-cart').textContent!.trim()).toBe(
        'Sepeti Kontrol Et',
      );
    });

    it('corrects the diacritics of a refused save', () => {
      component.increaseProductCount(10);
      component.openPaymentComponent();
      httpMock
        .expectOne(isCountUpdate)
        .flush('You cannot add more than available stock.', {
          status: 400,
          statusText: 'Bad Request',
        });
      fixture.detectChanges();

      expect(component.cartMessage).toBe(
        'Sepet güncellemesi kaydedilemedi. Lütfen tekrar deneyin.',
      );
      expect(query('cart-reconciliation-message').textContent).toContain('Lütfen');
      expect(query('cart-reconciliation-message').getAttribute('role')).toBe('alert');
    });

    it('names a removed product and a reduced one in correct Turkish, separately', () => {
      component.openPaymentComponent();
      httpMock.expectOne(isReconcile).flush({
        cart: [{ ...available }],
        removedProductIds: [11],
        reducedProductIds: [],
      });
      fixture.detectChanges();

      expect(component.cartMessage).toContain('1 ürün artık satışta olmadığı');
      expect(component.cartMessage).toContain('sepetten çıkarıldı.');
      expect(component.cartMessage).toContain('Lütfen sepeti kontrol edip onaylayın.');

      component.openPaymentComponent();
      httpMock.expectOne(isReconcile).flush({
        cart: [{ ...available }, { ...scarce }],
        removedProductIds: [],
        reducedProductIds: [10],
      });
      fixture.detectChanges();

      expect(component.cartMessage).toContain('1 ürünün miktarı kalan stoğa göre azaltıldı.');
      expect(component.cartMessage).not.toContain('çıkarıldı');
    });

    it('says the cart could not be verified rather than that it was lost', () => {
      component.openPaymentComponent();
      httpMock.expectOne(isReconcile).flush('nope', {
        status: 409,
        statusText: 'Conflict',
      });
      fixture.detectChanges();

      expect(component.cartMessage).toBe('Sepet doğrulanamadı. Lütfen tekrar deneyin.');
    });

    it('names the products its icon-only buttons act on', () => {
      const labels = Array.from<HTMLElement>(
        fixture.nativeElement.querySelectorAll('.cart-item button'),
      )
        .map((button) => button.getAttribute('aria-label'))
        .filter((label): label is string => label !== null);

      expect(labels).toContain('Sepetten çıkar: Tam Süt');
      expect(labels).toContain('Adedi azalt: Tam Süt');
      expect(labels).toContain('Adedi artır: Tam Süt');
      // And the stepper is one labelled group rather than two unrelated buttons.
      const group = fixture.nativeElement.querySelector(
        '.cart-item [role="group"]',
      ) as HTMLElement;
      expect(group.getAttribute('aria-label')).toBe('Tam Süt adedi');
      // The glyphs are decoration; the label above is the name.
      expect(fixture.nativeElement.querySelector('.cart-item i[aria-hidden="true"]')).toBeTruthy();
    });

    it('announces a quantity change without moving focus', () => {
      const readout = (): HTMLElement =>
        fixture.nativeElement.querySelector('.cart-item [aria-live="polite"]');
      expect(readout()).toBeTruthy();
      expect(readout().textContent!.trim()).toBe('2');

      const decrease = Array.from(
        fixture.nativeElement.querySelectorAll('.cart-item .btn-orange'),
      )[0] as HTMLButtonElement;
      const focusBefore = document.activeElement;
      decrease.click();
      fixture.detectChanges();

      // Re-queried: the row is re-rendered from the new cart, so the node that
      // carried the old count is gone.
      expect(readout().textContent!.trim()).toBe('1');
      expect(document.activeElement).toBe(focusBefore);
    });
  });

  describe('money formatting', () => {
    it('shows the total in Turkish, matching what the payment dialog will show', () => {
      component.totalPrice = 1234.5;
      fixture.detectChanges();

      expect(component.formattedTotal).toBe('1.234,50 TL');
      expect(query('cart-total').textContent).toContain('1.234,50 TL');
      // A row is priced the same way, so the two never disagree on screen.
      expect(query('cart-dialog').textContent).toContain('20,50 TL');
    });
  });

  describe('the checkout button', () => {
    it('says what it is doing for each in-flight state', () => {
      expect(query<HTMLButtonElement>('cart-checkout').textContent!.trim()).toBe(
        CART_COPY.confirm,
      );

      component.isCartWritePending = true;
      fixture.detectChanges();
      expect(query<HTMLButtonElement>('cart-checkout').textContent!.trim()).toBe(
        'Sepet kaydediliyor...',
      );

      component.isCartWritePending = false;
      component.isReconcilingCart = true;
      fixture.detectChanges();
      expect(query<HTMLButtonElement>('cart-checkout').textContent!.trim()).toBe(
        'Sepet kontrol ediliyor...',
      );

      component.isReconcilingCart = false;
      component.items = [];
      fixture.detectChanges();
      expect(query<HTMLButtonElement>('cart-checkout').textContent!.trim()).toBe(
        'Sepet Boş',
      );
    });

    it('is disabled and marked busy while a press is running', () => {
      component.increaseProductCount(10);
      component.openPaymentComponent();

      fixture.detectChanges();
      const button = query<HTMLButtonElement>('cart-checkout');
      expect(button.disabled).toBeTrue();
      expect(query('cart-dialog').getAttribute('aria-busy')).toBe('true');

      const update = httpMock.expectOne(isCountUpdate);
      update.flush('');
      // The reconciled cart is the one the write produced, so the confirmation the
      // customer already gave stands.
      httpMock.expectOne(isReconcile).flush({
        cart: [{ ...available, productCount: 3 }, { ...scarce }],
        removedProductIds: [],
        reducedProductIds: [],
      });
      flushImages();
      fixture.detectChanges();

      expect(component.isCartConfirmed).toBeTrue();
      expect(query<HTMLButtonElement>('cart-checkout').textContent!.trim()).toBe(
        CART_COPY.checkout,
      );
    });

    it('marks the confirmed state without repainting the button in a foreign colour', () => {
      component.openPaymentComponent();
      httpMock.expectOne(isReconcile).flush({
        cart: cartItems.map((item) => ({ ...item })),
        removedProductIds: [],
        reducedProductIds: [],
      });
      flushImages();
      fixture.detectChanges();

      const button = query<HTMLButtonElement>('cart-checkout');
      expect(component.isCartConfirmed).toBeTrue();
      expect(button.classList).toContain('cart-dialog__buy--confirmed');
      // Nothing writes an inline colour any more, so there is nothing a state
      // change can fail to undo.
      expect(button.style.backgroundColor).toBe('');
    });
  });

  describe('the empty-view action', () => {
    it('is a real, enabled, focusable button that reconciles when clicked', () => {
      component.items = [];
      component.totalPrice = 0;
      fixture.detectChanges();

      const action = query<HTMLButtonElement>('reconcile-empty-cart');
      expect(action.disabled).toBeFalse();
      expect(action.tagName).toBe('BUTTON');
      action.focus();
      expect(document.activeElement).toBe(action);

      action.click();

      httpMock.expectOne(isReconcile).flush({
        cart: [],
        removedProductIds: [10, 11],
        reducedProductIds: [],
      });
      flushImages();
      fixture.detectChanges();

      expect(query('cart-reconciliation-message').textContent).toContain('çıkarıldı');
      expect(httpMock.match(isCheckout).length).toBe(0);
    });

    it('refuses a second press while the first is still running', () => {
      component.items = [];
      fixture.detectChanges();

      query<HTMLButtonElement>('reconcile-empty-cart').click();
      fixture.detectChanges();
      expect(query<HTMLButtonElement>('reconcile-empty-cart').disabled).toBeTrue();

      // Taken first, so the assertion below is about a *second* reconciliation
      // rather than about the one the first press is still waiting on.
      const outstanding = httpMock.match(isReconcile);
      expect(outstanding.length).toBe(1);

      query<HTMLButtonElement>('reconcile-empty-cart').click();

      expect(httpMock.match(isReconcile).length).toBe(0);
      outstanding[0].flush({
        cart: [],
        removedProductIds: [],
        reducedProductIds: [],
      });
      fixture.detectChanges();
    });
  });

  describe('dialog semantics', () => {
    it('is a named modal dialog that can hold focus', () => {
      const dialog = query('cart-dialog');

      expect(dialog.getAttribute('role')).toBe('dialog');
      expect(dialog.getAttribute('aria-modal')).toBe('true');
      expect(dialog.getAttribute('tabindex')).toBe('-1');

      const labelledBy = dialog.getAttribute('aria-labelledby')!;
      const heading = fixture.nativeElement.querySelector(`#${labelledBy}`) as HTMLElement;
      expect(heading.textContent!.trim()).toBe(CART_COPY.title);
    });

    it('hides the click-catching scrim from assistive technology', () => {
      const scrim = fixture.nativeElement.querySelector(
        '#cart-overlay',
      ) as HTMLElement;
      expect(scrim.getAttribute('aria-hidden')).toBe('true');
      expect(scrim.hasAttribute('tabindex')).toBeFalse();
    });

    it('moves focus onto the dialog when it opens', () => {
      expect(document.activeElement).toBe(query('cart-dialog'));
    });
  });

  describe('keyboard', () => {
    it('closes on Escape, through the one path that saves staged edits first', () => {
      component.increaseProductCount(10);

      pressKey('Escape');

      // The edit is still unsaved, so Escape must not simply navigate away.
      const [update] = httpMock.match(isCountUpdate);
      expect(update).toBeTruthy();
      expect(update!.request.params.get('count')).toBe('3');
      expect(component.isCartWritePending).toBeTrue();

      update!.flush('');
      expect(component.isCartWritePending).toBeFalse();
    });

    it('leaves ordinary Tab presses alone while focus is still inside', () => {
      const firstRowIncrease = Array.from(
        fixture.nativeElement.querySelectorAll('.cart-item .btn-orange'),
      )[1] as HTMLButtonElement;
      expect(firstRowIncrease.disabled).toBeFalse();
      firstRowIncrease.focus();

      pressKey('Tab');

      // Not an end of the dialog, so the wrap must not fire and steal the press.
      expect(document.activeElement).toBe(firstRowIncrease);
    });

    it('wraps Tab at both ends instead of escaping into the storefront', () => {
      const close = query<HTMLButtonElement>('cart-close');
      const buy = query<HTMLButtonElement>('cart-checkout');

      buy.focus();
      pressKey('Tab');
      expect(document.activeElement).toBe(close);

      pressKey('Tab', true);
      expect(document.activeElement).toBe(buy);
    });

    /**
     * The launcher lives outside the dialog, on the page the dialog is covering.
     *
     * <p>A floating control out there is the one thing most likely to be reachable
     * by a stray Tab, and it is the one the customer would act on by accident - it
     * looks like a button, it is the only orange control on the screen, and on a
     * phone it sits right on top of the cart's own checkout action. So the
     * assertion is the whole cycle rather than one hop: focus starts on the
     * dialog, is driven forwards and backwards past both of its ends, and is
     * required to be inside the dialog every single time.
     */
    it('never lets focus reach a control outside the dialog', () => {
      const dialog = query('cart-dialog');
      // A stand-in for the support launcher, and for anything else the shell
      // renders beside the outlet: a real button, focusable, outside the dialog.
      const outside = document.createElement('button');
      outside.textContent = 'Canlı Destek';
      document.body.appendChild(outside);

      try {
        const visited: Element[] = [];
        dialog.focus();
        for (let step = 0; step < 12; step += 1) {
          pressKey('Tab');
          visited.push(document.activeElement as Element);
        }
        dialog.focus();
        for (let step = 0; step < 12; step += 1) {
          pressKey('Tab', true);
          visited.push(document.activeElement as Element);
        }

        expect(visited.every((element) => dialog.contains(element))).toBeTrue();
        expect(visited).not.toContain(outside);
        // The cycle really did move: a handler that simply swallowed every Tab
        // would pass the containment check above without trapping anything.
        expect(new Set(visited).size).toBeGreaterThan(1);
      } finally {
        outside.remove();
      }
    });

    it('leaves Escape to the payment dialog while that dialog is open', fakeAsync(() => {
      component.openPaymentComponent();
      httpMock.expectOne(isReconcile).flush({
        cart: cartItems.map((item) => ({ ...item })),
        removedProductIds: [],
        reducedProductIds: [],
      });
      flushImages();
      fixture.detectChanges();
      expect(component.isCartConfirmed).toBeTrue();

      component.openPaymentComponent();
      spyOn(PaymentComponent.prototype, 'loadStripe').and.resolveTo();
      fixture.detectChanges();
      expect(component.isPaymentPhaseActive).toBeTrue();

      const prepared = httpMock.expectOne(isCheckout);
      prepared.flush({
        checkoutId: 'checkout-1',
        status: 'PREPARED',
        totalAmount: 55,
        amountMinor: 5500,
        currency: 'TRY',
      });
      fixture.detectChanges();

      // Escape arrives at the payment dialog first. The cart must not answer it
      // as well: that would tear down a checkout the dialog is only cancelling,
      // and a charge could already be in flight behind it.
      pressKey('Escape');
      tick();
      fixture.detectChanges();

      const cancel = httpMock.expectOne((request) =>
        request.url.endsWith('/cancel'),
      );
      expect(cancel.request.method).toBe('POST');
      expect(cancel.request.url).toContain('checkout-1');
      // Still open while the cancellation is in flight, and nothing prepared a
      // replacement to stand in for it.
      expect(component.isPaymentPhaseActive).toBeTrue();
      expect(httpMock.match(isCheckout).length).toBe(0);

      cancel.flush({ checkoutId: 'checkout-1', status: 'CANCELLED' });
      fixture.detectChanges();
      expect(component.isPaymentPhaseActive).toBeFalse();
      flush();
    }));
  });
});