import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick,
  discardPeriodicTasks,
} from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';

import { PAYMENT_COPY, PaymentComponent } from './payment.component';
import { RestService } from '../../../../services/rest/rest.service';
import { ICheckoutResponse } from '../../../../interfaces/ICheckoutResponse';
import { IPaymentStatus } from '../../../../interfaces/IPaymentStatus';

describe('PaymentComponent', () => {
  let component: PaymentComponent;
  let fixture: ComponentFixture<PaymentComponent>;
  let restService: jasmine.SpyObj<RestService>;

  function createCheckout(
    overrides?: Partial<ICheckoutResponse>,
  ): ICheckoutResponse {
    return {
      checkoutId: 'checkout-1',
      status: 'PREPARED',
      totalAmount: 21,
      amountMinor: 2100,
      currency: 'try',
      ...overrides,
    };
  }

  function createFinalizedStatus(
    overrides?: Partial<IPaymentStatus>,
  ): IPaymentStatus {
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

  function createChargeSuccessResponse() {
    return {
      success: true,
      pending: false,
      checkoutId: 'checkout-1',
      status: 'CONSUMED',
      state: 'ORDER_FINALIZED',
      chargeId: 'ch_1',
    };
  }

  beforeEach(async () => {
    restService = jasmine.createSpyObj('RestService', [
      'prepareCheckout',
      'chargeCheckout',
      'getCheckoutStatus',
      'getPaymentStatus',
      'cancelCheckout',
    ]);

    await TestBed.configureTestingModule({
      imports: [PaymentComponent],
      providers: [{ provide: RestService, useValue: restService }],
    }).compileComponents();

    spyOn(window, 'alert').and.stub();

    restService.prepareCheckout.and.callFake(() => of(createCheckout()));
    restService.chargeCheckout.and.callFake(() =>
      of(createChargeSuccessResponse()),
    );
    restService.getPaymentStatus.and.callFake(() =>
      of(createFinalizedStatus()),
    );

    fixture = TestBed.createComponent(PaymentComponent);
    component = fixture.componentInstance;

    spyOn(component as any, 'loadStripe').and.returnValue(Promise.resolve());
    component.stripe = {
      createToken: jasmine
        .createSpy('createToken')
        .and.returnValue(Promise.resolve({ token: { id: 'tok_visa' } })),
    } as any;
    component.card = { destroy: jasmine.createSpy('destroy') } as any;

    fixture.detectChanges();
  });

  it('prepares the checkout before any charge and displays the server total', () => {
    const expected = createCheckout();
    expect(restService.prepareCheckout).toHaveBeenCalled();
    expect(component.checkout).toEqual(expected);
    expect(component.checkout?.status).toBe('PREPARED');
    expect(component.displayTotal).toBe('21.00');
    expect(component.displayCurrency).toBe('TRY');
    expect(restService.chargeCheckout).not.toHaveBeenCalled();
  });

  it('emits a distinct checkout object for every preparation', () => {
    const seen: ICheckoutResponse[] = [];
    restService.prepareCheckout().subscribe((value) => seen.push(value));
    restService.prepareCheckout().subscribe((value) => seen.push(value));

    expect(seen.length).toBe(2);
    expect(seen[0]).not.toBe(seen[1]);
    expect(seen[0]).toEqual(seen[1]);
  });

  it('does not leak a mutated checkout status into the next preparation', () => {
    let first!: ICheckoutResponse;
    let second!: ICheckoutResponse;
    restService.prepareCheckout().subscribe((value) => (first = value));
    // Simulate PaymentComponent.processPayment mutating the held reference.
    first.status = 'PAYMENT_PROCESSING';
    restService.prepareCheckout().subscribe((value) => (second = value));

    expect(second).not.toBe(first);
    expect(second.status).toBe('PREPARED');
    expect(first.status).toBe('PAYMENT_PROCESSING');
  });

  it('refreshes the cart after preparation because the snapshot was reserved', () => {
    expect(component.checkout?.status).toBe('PREPARED');
    expect(component.isProcessing).toBeFalse();
    const emitted = spyOn(component.cartPrepared, 'emit');
    component.prepareCheckout();

    expect(emitted).toHaveBeenCalledTimes(1);
  });

  it('does not request a charge when no checkout has been prepared', async () => {
    component.checkout = null;

    await component.handlePayment();

    expect(component.stripe!.createToken).not.toHaveBeenCalled();
    expect(restService.chargeCheckout).not.toHaveBeenCalled();
  });

  it('suppresses duplicate submissions while a charge is pending', async () => {
    expect(component.checkout?.status).toBe('PREPARED');
    const first = component.handlePayment();
    const second = component.handlePayment();

    await Promise.all([first, second]);

    expect(restService.chargeCheckout).toHaveBeenCalledTimes(1);
    expect(component.stripe!.createToken).toHaveBeenCalledTimes(1);
  });

  it('sends the server checkout id and the payment token', async () => {
    expect(component.checkout?.status).toBe('PREPARED');
    await component.handlePayment();

    expect(restService.chargeCheckout).toHaveBeenCalledWith(
      'checkout-1',
      'tok_visa',
    );
  });

  it('emits payment success on a finalized payment', async () => {
    expect(component.checkout?.status).toBe('PREPARED');
    const success = spyOn(component.paymentSuccess, 'emit');

    await component.handlePayment();

    expect(success).toHaveBeenCalled();
    expect(component.isProcessing).toBeFalse();
  });

  it('does not attempt tokenization when Stripe did not initialize', async () => {
    component.stripe = null;
    component.card = null;

    await component.handlePayment();

    expect(component.errorMessage).toBe(PAYMENT_COPY.formInitFailed);
    expect(component.isProcessing).toBeFalse();
    expect(restService.chargeCheckout).not.toHaveBeenCalled();
  });

  it('shows the Stripe validation failure without submitting a charge', async () => {
    component.stripe!.createToken = jasmine
      .createSpy('createToken')
      .and.returnValue(Promise.resolve({ error: { message: 'Card declined' } }));

    await component.handlePayment();

    // The provider's own reason is kept - it names the problem - but it is led by
    // our own Turkish explanation, and it never says the payment went through.
    expect(component.errorMessage).toContain(PAYMENT_COPY.cardRejected);
    expect(component.errorMessage).toContain('Card declined');
    expect(component.isProcessing).toBeFalse();
    expect(restService.chargeCheckout).not.toHaveBeenCalled();
  });

  it('keeps a failed checkout attached to the same id without preparing a replacement', async () => {
    restService.chargeCheckout.and.returnValue(
      of({ ...createChargeSuccessResponse(), success: false, pending: false }),
    );
    restService.prepareCheckout.calls.reset();

    await component.handlePayment();

    expect(component.errorMessage).toBe(PAYMENT_COPY.chargeFailed);
    expect(restService.chargeCheckout).toHaveBeenCalledOnceWith('checkout-1', 'tok_visa');
    expect(restService.prepareCheckout).not.toHaveBeenCalled();
    expect(component.checkout?.checkoutId).toBe('checkout-1');
  });

  it('handles rejected token creation without losing the prepared checkout', async () => {
    component.stripe!.createToken = jasmine
      .createSpy('createToken')
      .and.returnValue(Promise.reject(new Error('Stripe unavailable')));

    await component.handlePayment();

    expect(component.errorMessage).toBe(PAYMENT_COPY.tokenFailed);
    expect(component.isProcessing).toBeFalse();
    expect(component.checkout?.checkoutId).toBe('checkout-1');
    expect(restService.chargeCheckout).not.toHaveBeenCalled();
    expect(restService.prepareCheckout).toHaveBeenCalledTimes(1);
  });

  it('recovers the existing checkout status on a network error instead of preparing again', async () => {
    expect(component.checkout?.status).toBe('PREPARED');
    restService.chargeCheckout.and.returnValue(
      throwError(() => new Error('timeout')),
    );

    await component.handlePayment();

    expect(restService.getPaymentStatus).toHaveBeenCalledWith('checkout-1');
    expect(restService.prepareCheckout).toHaveBeenCalledTimes(1);
  });

  it('polls the same checkout when the server reports a pending attempt', () => {
    restService.getPaymentStatus.and.callFake(() =>
      of(
        createFinalizedStatus({
          finalized: false,
          pending: true,
          state: 'PROCESSING',
        }),
      ),
    );

    component.pollPaymentStatus('checkout-1', 0);

    expect(restService.getPaymentStatus).toHaveBeenCalledWith('checkout-1');
    expect(component.pendingMessage.length).toBeGreaterThanOrEqual(0);
    expect(component.errorMessage).toBe(PAYMENT_COPY.stillProcessing);
  });

  it('keeps polling a pending charge against the original checkout only', fakeAsync(() => {
    restService.chargeCheckout.and.returnValue(
      of({ ...createChargeSuccessResponse(), success: false, pending: true }),
    );
    restService.getPaymentStatus.and.returnValue(
      of(createFinalizedStatus({ finalized: false, pending: true, state: 'PROCESSING' })),
    );
    restService.prepareCheckout.calls.reset();

    void component.handlePayment();
    tick(0);
    tick(2000);

    expect(restService.chargeCheckout).toHaveBeenCalledOnceWith('checkout-1', 'tok_visa');
    expect(restService.getPaymentStatus).toHaveBeenCalledWith('checkout-1');
    expect(restService.prepareCheckout).not.toHaveBeenCalled();
    expect(component.checkout?.checkoutId).toBe('checkout-1');
    expect(component.pendingMessage).toBe(PAYMENT_COPY.verifying);
    tick(10000);
    discardPeriodicTasks();
  }));

  it('does not treat a gracefully pending checkout as a hard failure', fakeAsync(() => {
    restService.getPaymentStatus.and.callFake(() =>
      of(
        createFinalizedStatus({
          finalized: false,
          pending: true,
          state: 'CHARGE_SUCCEEDED',
        }),
      ),
    );

    component.isProcessing = true;
    component.pollPaymentStatus('checkout-1', 3);

    expect(component.pendingMessage).toBe(PAYMENT_COPY.verifying);
    expect(component.isProcessing).toBeTrue();

    // Deterministically advance the scheduled re-poll chain instead of leaking
    // a real 2s timer into the next test, then clean up any chained timers.
    tick(10000);
    discardPeriodicTasks();
  }));

  it('tears down the card, pending status request, and pending poll timer', fakeAsync(() => {
    const pendingRequest = new Subject<IPaymentStatus>();
    const cardDestroy = component.card!.destroy as jasmine.Spy;
    restService.getPaymentStatus.and.returnValue(pendingRequest);
    component.pollPaymentStatus('checkout-1', 2);
    pendingRequest.next(
      createFinalizedStatus({ finalized: false, pending: true, state: 'PROCESSING' }),
    );

    fixture.destroy();
    tick(2000);

    expect(component.card).toBeNull();
    expect(component.stripe).toBeNull();
    expect(cardDestroy).toHaveBeenCalled();
    expect(restService.getPaymentStatus).toHaveBeenCalledTimes(1);
  }));

  it('cancels a prepared checkout when the modal is closed before payment', () => {
    restService.cancelCheckout.and.callFake(() => of(createCheckout()));
    const closed = spyOn(component.closePaymentComponentEvent, 'emit');
    component.checkout = createCheckout({ status: 'PREPARED' });
    component.isProcessing = false;

    component.closePaymentComponent();

    expect(restService.cancelCheckout).toHaveBeenCalledWith('checkout-1');
    expect(closed).toHaveBeenCalled();
  });

  it('never cancels a processing checkout when the modal is closed', () => {
    restService.cancelCheckout.calls.reset();
    const closed = spyOn(component.closePaymentComponentEvent, 'emit');
    component.checkout = createCheckout({ status: 'PAYMENT_PROCESSING' });
    component.isProcessing = true;

    component.closePaymentComponent();

    expect(restService.cancelCheckout).not.toHaveBeenCalled();
    expect(closed).not.toHaveBeenCalled();
    expect(component.pendingMessage).toBe(PAYMENT_COPY.verifyingOnClose);
  });

  it('never cancels when the local status already moved to processing', () => {
    restService.cancelCheckout.calls.reset();
    component.checkout = createCheckout({ status: 'PAYMENT_PROCESSING' });
    component.isProcessing = false;

    component.closePaymentComponent();

    expect(restService.cancelCheckout).not.toHaveBeenCalled();
  });

  it('does not start a replacement checkout while payment is processing', () => {
    restService.prepareCheckout.calls.reset();
    component.checkout = createCheckout({ status: 'PAYMENT_PROCESSING' });
    component.isProcessing = true;

    component.prepareCheckout();

    expect(restService.prepareCheckout).not.toHaveBeenCalled();
  });

  function pendingCancelConflict() {
    return {
      status: 409,
      error: {
        code: 'PAYMENT_RECONCILIATION_PENDING',
        pending: true,
        status: 409,
        checkoutId: 'checkout-1',
      },
    };
  }

  it('recovers the same checkout on a typed pending cancel conflict', () => {
    restService.cancelCheckout.and.returnValue(
      throwError(() => pendingCancelConflict()),
    );
    restService.getPaymentStatus.and.callFake(() =>
      of(
        createFinalizedStatus({
          finalized: false,
          pending: true,
          state: 'PROCESSING',
        }),
      ),
    );
    restService.prepareCheckout.calls.reset();
    const closed = spyOn(component.closePaymentComponentEvent, 'emit');
    component.checkout = createCheckout({ status: 'PREPARED' });
    component.isProcessing = false;

    component.closePaymentComponent();

    expect(restService.cancelCheckout).toHaveBeenCalledWith('checkout-1');
    expect(restService.getPaymentStatus).toHaveBeenCalledWith('checkout-1');
    expect(restService.prepareCheckout).not.toHaveBeenCalled();
    expect(restService.chargeCheckout).not.toHaveBeenCalled();
    expect(closed).not.toHaveBeenCalled();
    expect(component.checkout?.checkoutId).toBe('checkout-1');
    expect(component.pendingMessage.length).toBeGreaterThan(0);
  });

  it('never creates a replacement checkout in the pending cancel path', fakeAsync(() => {
    restService.cancelCheckout.and.returnValue(
      throwError(() => pendingCancelConflict()),
    );
    restService.getPaymentStatus.and.callFake(() =>
      of(
        createFinalizedStatus({
          finalized: false,
          pending: true,
          state: 'CHARGE_SUCCEEDED',
        }),
      ),
    );
    restService.prepareCheckout.calls.reset();
    component.checkout = createCheckout({ status: 'PREPARED' });
    component.isProcessing = false;

    component.closePaymentComponent();
    tick(10000);
    discardPeriodicTasks();

    expect(restService.prepareCheckout).not.toHaveBeenCalled();
    expect(component.checkout?.checkoutId).toBe('checkout-1');
    expect(component.isReconciliationPendingConflict(pendingCancelConflict()))
      .toBeTrue();
  }));

  it('fails closed on an untyped cancel error without replacing the checkout', () => {
    restService.cancelCheckout.and.returnValue(
      throwError(() => ({ status: 500, error: 'boom' })),
    );
    restService.prepareCheckout.calls.reset();
    restService.getPaymentStatus.calls.reset();
    const closed = spyOn(component.closePaymentComponentEvent, 'emit');
    component.checkout = createCheckout({ status: 'PREPARED' });
    component.isProcessing = false;

    component.closePaymentComponent();

    expect(restService.prepareCheckout).not.toHaveBeenCalled();
    expect(restService.getPaymentStatus).not.toHaveBeenCalled();
    expect(component.checkout).toBeNull();
    expect(closed).toHaveBeenCalled();
    expect(component.errorMessage.length).toBeGreaterThan(0);
    expect(component.isReconciliationPendingConflict({
      status: 500,
      error: 'boom',
    })).toBeFalse();
  });

  it('does not poll status for a typed non-pending cancel conflict', () => {
    restService.cancelCheckout.and.returnValue(
      throwError(() => ({
        status: 409,
        error: {
          code: 'CHECKOUT_NOT_CANCELLABLE',
          pending: false,
          status: 409,
          checkoutId: 'checkout-1',
        },
      })),
    );
    restService.prepareCheckout.calls.reset();
    restService.getPaymentStatus.calls.reset();
    component.checkout = createCheckout({ status: 'PREPARED' });
    component.isProcessing = false;

    component.closePaymentComponent();

    expect(restService.getPaymentStatus).not.toHaveBeenCalled();
    expect(restService.prepareCheckout).not.toHaveBeenCalled();
    expect(component.checkout).toBeNull();
  });
});
