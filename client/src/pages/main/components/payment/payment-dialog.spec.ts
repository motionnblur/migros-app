import { ComponentFixture, TestBed } from '@angular/core/testing';
import type { Stripe } from '@stripe/stripe-js';
import { of, throwError } from 'rxjs';

import {
  PAYMENT_COPY,
  PaymentComponent,
  createCardElements,
} from './payment.component';
import { RestService } from '../../../../services/rest/rest.service';
import { ICheckoutResponse } from '../../../../interfaces/ICheckoutResponse';
import { IPaymentStatus } from '../../../../interfaces/IPaymentStatus';
import { formatAmount, formatCurrencyLabel, formatMoney } from '../../helpers/money-format';

/**
 * What the payment dialog says, how it behaves as a dialog, and what it refuses
 * to do.
 *
 * <p>The durability rules are pinned in `payment.component.spec.ts`; this suite is
 * about everything a customer touches - the wording, the announced states, the
 * disabled controls, the keyboard, and the one thing the dialog must never do,
 * which is to answer an ambiguous charge by starting a second one.
 *
 * <p>Every assertion goes through the DOM wherever the point *is* the DOM: a
 * button that no customer can click proves nothing about a handler, so the
 * presses below are real clicks and the focus movements are real ones.
 */
describe('PaymentComponent dialog', () => {
  let restService: jasmine.SpyObj<RestService>;

  function createCheckout(overrides?: Partial<ICheckoutResponse>): ICheckoutResponse {
    return {
      checkoutId: 'checkout-1',
      status: 'PREPARED',
      totalAmount: 1234.5,
      amountMinor: 123450,
      currency: 'TRY',
      ...overrides,
    };
  }

  function createStatus(overrides?: Partial<IPaymentStatus>): IPaymentStatus {
    return {
      checkoutId: 'checkout-1',
      checkoutStatus: 'CONSUMED',
      state: 'ORDER_FINALIZED',
      chargeId: 'ch_1',
      finalized: true,
      pending: false,
      refunded: false,
      ...overrides,
    };
  }

  /**
   * Mounts the dialog with Stripe stubbed out.
   *
   * <p>Stripe is replaced rather than loaded: `loadStripe` reaches the network,
   * and a suite that needed the network would be a suite that stops proving
   * anything the moment Stripe is slow.
   */
  function openDialog(): ComponentFixture<PaymentComponent> {
    const fixture = TestBed.createComponent(PaymentComponent);
    const component = fixture.componentInstance;
    component.stripe = {
      createToken: jasmine
        .createSpy('createToken')
        .and.returnValue(Promise.resolve({ token: { id: 'tok_visa' } })),
    } as unknown as Stripe;
    component.card = { destroy: jasmine.createSpy('destroy') } as never;
    fixture.detectChanges();
    return fixture;
  }

  function query<T extends HTMLElement>(fixture: ComponentFixture<PaymentComponent>, testId: string): T {
    return fixture.nativeElement.querySelector(`[data-testid="${testId}"]`) as T;
  }

  function pressKey(key: string, shiftKey = false): void {
    document.dispatchEvent(
      new KeyboardEvent('keydown', { key, shiftKey, bubbles: true }),
    );
  }

  beforeEach(async () => {
    restService = jasmine.createSpyObj('RestService', [
      'prepareCheckout',
      'chargeCheckout',
      'getCheckoutStatus',
      'getPaymentStatus',
      'cancelCheckout',
    ]);
    restService.prepareCheckout.and.callFake(() => of(createCheckout()));
    restService.cancelCheckout.and.callFake(() => of(createCheckout()));

    spyOn(window, 'alert').and.stub();
    spyOn(PaymentComponent.prototype, 'loadStripe').and.resolveTo();

    await TestBed.configureTestingModule({
      imports: [PaymentComponent],
      providers: [{ provide: RestService, useValue: restService }],
    }).compileComponents();
  });

  describe('Turkish copy', () => {
    it('titles the dialog, the total and the action in Turkish', () => {
      const fixture = openDialog();

      expect(query(fixture, 'payment-dialog')).toBeTruthy();
      expect(PAYMENT_COPY.dialogTitle).toBe('Güvenli Ödeme');
      expect(fixture.nativeElement.textContent).toContain('Güvenli Ödeme');
      expect(fixture.nativeElement.textContent).toContain('Toplam Tutar');
      expect(query<HTMLButtonElement>(fixture, 'payment-submit').textContent!.trim()).toBe(
        'Ödemeyi Tamamla',
      );

      fixture.destroy();
    });

    it('has no English left in any of the shipped states', () => {
      const english = [
        'Secure Payment',
        'Pay Now',
        'Processing',
        'Card',
        'Total:',
        'Terms of Service',
        'Privacy Policy',
        'Please try again',
      ];
      const rendered = Object.values(PAYMENT_COPY).join(' ');

      for (const phrase of english) {
        expect(rendered).not.toContain(phrase);
      }
    });

    it('reports a card the provider refused in Turkish, without claiming a charge', async () => {
      const fixture = openDialog();
      const component = fixture.componentInstance;
      component.stripe!.createToken = jasmine
        .createSpy('createToken')
        .and.returnValue(Promise.resolve({ error: { message: 'Your card was declined.' } }));

      await component.handlePayment();
      fixture.detectChanges();

      const error = query(fixture, 'payment-error');
      expect(error.textContent).toContain('kabul edilmedi');
      // The provider's own reason is kept, because it names the problem...
      expect(error.textContent).toContain('Your card was declined.');
      // ...and nothing in it can be read as the money having been taken.
      expect(error.textContent).not.toContain('başarılı');
      expect(restService.chargeCheckout).not.toHaveBeenCalled();

      fixture.destroy();
    });

    it('names a refunded payment as refunded rather than as a failure', () => {
      const fixture = openDialog();
      const component = fixture.componentInstance;
      restService.getPaymentStatus.and.returnValue(
        of(
          createStatus({
            finalized: false,
            pending: false,
            refunded: true,
            state: 'REFUNDED',
          }),
        ),
      );

      component.pollPaymentStatus('checkout-1', 0);
      fixture.detectChanges();

      expect(query(fixture, 'payment-error').textContent).toContain('iade edildi');

      fixture.destroy();
    });

    it('tells an unrecognised status apart from a known failure', () => {
      const fixture = openDialog();
      const component = fixture.componentInstance;
      restService.getPaymentStatus.and.returnValue(
        of(createStatus({ finalized: false, pending: false, state: 'SOMETHING_NEW' })),
      );

      component.pollPaymentStatus('checkout-1', 0);
      fixture.detectChanges();

      expect(component.errorMessage).toBe(PAYMENT_COPY.unknownStatus);
      expect(component.errorMessage).not.toBe(PAYMENT_COPY.chargeFailed);

      fixture.destroy();
    });

    it('announces the error and the pending state through different roles', () => {
      const fixture = openDialog();
      const component = fixture.componentInstance;

      component.pendingMessage = PAYMENT_COPY.verifying;
      fixture.detectChanges();

      expect(query(fixture, 'payment-pending').getAttribute('role')).toBe('status');
      expect(query(fixture, 'payment-error')).toBeNull();

      component.errorMessage = PAYMENT_COPY.chargeFailed;
      fixture.detectChanges();

      expect(query(fixture, 'payment-error').getAttribute('role')).toBe('alert');
      // The pending line is still a status, not an alert: an ambiguous charge is
      // something already in progress, not an interruption.
      expect(query(fixture, 'payment-pending').getAttribute('role')).toBe('status');

      fixture.destroy();
    });
  });

  describe('money formatting', () => {
    it('renders the server total in Turkish, with the currency the server named', () => {
      const fixture = openDialog();

      expect(formatAmount(1234.5)).toBe('1.234,50');
      expect(formatCurrencyLabel('try')).toBe('TL');
      expect(formatMoney(1234.5, 'TRY')).toBe('1.234,50 TL');
      expect(query(fixture, 'payment-total').textContent).toContain('1.234,50 TL');

      fixture.destroy();
    });

    it('never invents a currency the API did not send', () => {
      expect(formatCurrencyLabel(undefined)).toBe('TL');
      expect(formatCurrencyLabel('')).toBe('TL');
      expect(formatCurrencyLabel('EUR')).toBe('EUR');
      expect(formatAmount('not a number')).toBe('0,00');
    });

    it('keeps the raw server currency code alongside the rendered label', () => {
      const fixture = openDialog();
      const component = fixture.componentInstance;

      expect(component.displayCurrency).toBe('TRY');
      expect(component.displayTotal).toBe('1234.50');
      expect(component.displayCurrencyLabel).toBe('TL');

      fixture.destroy();
    });
  });

  describe('the placeholder terms and privacy links', () => {
    it('links to nothing and claims no agreement, because neither exists', () => {
      const fixture = openDialog();

      const links = Array.from<HTMLAnchorElement>(
        fixture.nativeElement.querySelectorAll('a'),
      );

      expect(links.length).toBe(0);
      expect(fixture.nativeElement.innerHTML).not.toContain('href="#"');
      expect(fixture.nativeElement.textContent).not.toContain('agree');
      expect(fixture.nativeElement.textContent).not.toContain('Terms');
      expect(fixture.nativeElement.textContent).not.toContain('Privacy');
      // What it says instead is a statement this component can actually back up.
      expect(fixture.nativeElement.textContent).toContain('ödeme sağlayıcısına');

      fixture.destroy();
    });
  });

  describe('the Stripe card field', () => {
    it('is created in Turkish rather than left to the browser locale', () => {
      const elements = jasmine.createSpyObj('elements', ['create']);
      const stripe = jasmine.createSpyObj('stripe', ['elements']);
      stripe.elements.and.returnValue(elements);

      createCardElements(stripe as unknown as Stripe);

      expect(stripe.elements).toHaveBeenCalledOnceWith({ locale: 'tr' });
    });
  });

  describe('dialog semantics', () => {
    it('is a named modal dialog that can hold focus', () => {
      const fixture = openDialog();
      const dialog = query(fixture, 'payment-dialog');

      expect(dialog.getAttribute('role')).toBe('dialog');
      expect(dialog.getAttribute('aria-modal')).toBe('true');
      expect(dialog.getAttribute('tabindex')).toBe('-1');

      // The name is the heading's, not a second copy of it that could drift.
      const labelledBy = dialog.getAttribute('aria-labelledby')!;
      const heading = fixture.nativeElement.querySelector(`#${labelledBy}`);
      expect(heading.textContent.trim()).toBe(PAYMENT_COPY.dialogTitle);

      fixture.destroy();
    });

    it('gives the close control a name instead of a bare glyph', () => {
      const fixture = openDialog();
      const close = query(fixture, 'payment-close');

      expect(close.getAttribute('aria-label')).toBe(PAYMENT_COPY.close);
      expect(close.textContent!.trim()).toBe('×');

      fixture.destroy();
    });
  });

  describe('keyboard', () => {
    it('moves focus onto the dialog when it opens', () => {
      const fixture = openDialog();

      expect(document.activeElement).toBe(query(fixture, 'payment-dialog'));

      fixture.destroy();
    });

    it('hands focus back to whatever opened it', () => {
      const opener = document.createElement('button');
      document.body.appendChild(opener);
      opener.focus();
      expect(document.activeElement).toBe(opener);

      const fixture = openDialog();
      expect(document.activeElement).toBe(query(fixture, 'payment-dialog'));

      fixture.destroy();

      expect(document.activeElement).toBe(opener);
      opener.remove();
    });

    it('does not hand focus to a page that is no longer there', () => {
      const opener = document.createElement('button');
      document.body.appendChild(opener);
      opener.focus();

      const fixture = openDialog();
      // The customer navigated away, taking the button with them.
      opener.remove();
      fixture.destroy();

      // Nothing to assert but that it did not throw, and that focus did not end
      // up on a detached node.
      expect(document.activeElement).not.toBe(opener);
    });

    it('cancels an untouched prepared checkout on Escape, through the close path', () => {
      const fixture = openDialog();
      const component = fixture.componentInstance;
      const closed = spyOn(component.closePaymentComponentEvent, 'emit');

      pressKey('Escape');

      expect(restService.cancelCheckout).toHaveBeenCalledOnceWith('checkout-1');
      expect(closed).toHaveBeenCalledTimes(1);

      fixture.destroy();
    });

    it('refuses to cancel on Escape while a charge may be in flight', () => {
      const fixture = openDialog();
      const component = fixture.componentInstance;
      const closed = spyOn(component.closePaymentComponentEvent, 'emit');
      component.checkout = createCheckout({ status: 'PAYMENT_PROCESSING' });
      component.isProcessing = true;
      fixture.detectChanges();

      pressKey('Escape');
      fixture.detectChanges();

      expect(restService.cancelCheckout).not.toHaveBeenCalled();
      expect(closed).not.toHaveBeenCalled();
      expect(component.pendingMessage).toBe(PAYMENT_COPY.verifyingOnClose);
      expect(query(fixture, 'payment-pending').textContent).toContain('doğrulanıyor');

      fixture.destroy();
    });

    it('keeps Tab inside the dialog rather than out into the storefront behind it', () => {
      const fixture = openDialog();
      const close = query(fixture, 'payment-close');
      const submit = query(fixture, 'payment-submit');

      submit.focus();
      expect(document.activeElement).toBe(submit);

      pressKey('Tab');
      expect(document.activeElement).toBe(close);

      pressKey('Tab', true);
      expect(document.activeElement).toBe(submit);

      fixture.destroy();
    });

    it('steps in from the dialog itself, which is where focus starts', () => {
      const fixture = openDialog();
      const dialog = query(fixture, 'payment-dialog');
      dialog.focus();

      pressKey('Tab');

      expect(document.activeElement).toBe(query(fixture, 'payment-close'));

      fixture.destroy();
    });

    it('stops listening for keys once the dialog is gone', () => {
      const fixture = openDialog();
      const component = fixture.componentInstance;
      restService.cancelCheckout.calls.reset();
      const closed = spyOn(component.closePaymentComponentEvent, 'emit');

      fixture.destroy();
      pressKey('Escape');

      expect(restService.cancelCheckout).not.toHaveBeenCalled();
      expect(closed).not.toHaveBeenCalled();
    });
  });

  describe('the one action, and when it is refused', () => {
    it('refuses to charge until a checkout snapshot exists', () => {
      const fixture = openDialog();
      const component = fixture.componentInstance;
      component.checkout = null;
      fixture.detectChanges();

      const submit = query<HTMLButtonElement>(fixture, 'payment-submit');
      expect(submit.disabled).toBeTrue();
      expect(submit.textContent!.trim()).toBe(PAYMENT_COPY.payNow);

      submit.click();
      expect(component.stripe!.createToken).not.toHaveBeenCalled();
      expect(restService.chargeCheckout).not.toHaveBeenCalled();

      fixture.destroy();
    });

    it('says what it is doing while the snapshot is still being prepared', () => {
      const fixture = openDialog();
      const component = fixture.componentInstance;
      component.isPreparing = true;
      component.checkout = null;
      fixture.detectChanges();

      const submit = query<HTMLButtonElement>(fixture, 'payment-submit');
      expect(submit.disabled).toBeTrue();
      expect(submit.textContent!.trim()).toBe(PAYMENT_COPY.preparing);
      expect(query(fixture, 'payment-total-pending')).toBeTruthy();

      fixture.destroy();
    });

    it('changes the label and stays disabled for the whole charge', async () => {
      const fixture = openDialog();
      const component = fixture.componentInstance;
      component.stripe!.createToken = jasmine
        .createSpy('createToken')
        .and.returnValue(
          new Promise((resolve) => {
            setTimeout(() => resolve({ token: { id: 'tok_visa' } }), 0);
          }),
        );
      fixture.detectChanges();

      const press = query<HTMLButtonElement>(fixture, 'payment-submit');
      const running = component.handlePayment();
      fixture.detectChanges();

      expect(press.disabled).toBeTrue();
      expect(press.textContent!.trim()).toBe(PAYMENT_COPY.processing);
      expect(query(fixture, 'payment-dialog').getAttribute('aria-busy')).toBe('true');
      // A second press on a disabled control must still reach nothing.
      press.click();
      expect(component.stripe!.createToken).toHaveBeenCalledTimes(1);

      await running;
      fixture.destroy();
    });

    it('recovers the same checkout after an ambiguous charge instead of replacing it', async () => {
      const fixture = openDialog();
      const component = fixture.componentInstance;
      restService.prepareCheckout.calls.reset();
      restService.chargeCheckout.and.returnValue(
        throwError(() => new Error('timeout')),
      );
      restService.getPaymentStatus.and.returnValue(
        of(createStatus({ finalized: false, pending: true, state: 'PROCESSING' })),
      );
      fixture.detectChanges();

      await component.handlePayment();

      // The decisive assertions: the same checkout id is polled, and nothing
      // prepares a second one. A replacement checkout here is a second charge.
      expect(restService.chargeCheckout).toHaveBeenCalledOnceWith('checkout-1', 'tok_visa');
      expect(restService.getPaymentStatus).toHaveBeenCalledWith('checkout-1');
      expect(restService.prepareCheckout).not.toHaveBeenCalled();
      expect(component.checkout?.checkoutId).toBe('checkout-1');
      expect(component.isChargeInFlight).toBeTrue();
      fixture.detectChanges();
      // And the button is still refused, because the money may already be gone.
      expect(query<HTMLButtonElement>(fixture, 'payment-submit').disabled).toBeTrue();

      fixture.destroy();
    });

    it('refuses to close a processing checkout from the close button as well', () => {
      const fixture = openDialog();
      const component = fixture.componentInstance;
      component.checkout = createCheckout({ status: 'PAYMENT_PROCESSING' });
      component.isProcessing = true;
      fixture.detectChanges();

      query<HTMLButtonElement>(fixture, 'payment-close').click();

      expect(restService.cancelCheckout).not.toHaveBeenCalled();
      expect(component.checkout?.checkoutId).toBe('checkout-1');

      fixture.destroy();
    });
  });
});