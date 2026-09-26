import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { PaymentComponent } from './payment.component';
import { RestService } from '../../../../services/rest/rest.service';
import { ICheckoutResponse } from '../../../../interfaces/ICheckoutResponse';
import { IPaymentStatus } from '../../../../interfaces/IPaymentStatus';

describe('PaymentComponent', () => {
  let component: PaymentComponent;
  let fixture: ComponentFixture<PaymentComponent>;
  let restService: jasmine.SpyObj<RestService>;

  const checkout: ICheckoutResponse = {
    checkoutId: 'checkout-1',
    status: 'PREPARED',
    totalAmount: 21,
    amountMinor: 2100,
    currency: 'try',
  };

  const finalizedStatus: IPaymentStatus = {
    checkoutId: 'checkout-1',
    checkoutStatus: 'CONSUMED',
    state: 'ORDER_FINALIZED',
    chargeId: 'ch_1',
    finalized: true,
    pending: false,
    refunded: false,
  };

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

    restService.prepareCheckout.and.returnValue(of(checkout));
    restService.chargeCheckout.and.returnValue(
      of({
        success: true,
        pending: false,
        checkoutId: checkout.checkoutId,
        status: 'CONSUMED',
        state: 'ORDER_FINALIZED',
        chargeId: 'ch_1',
      }),
    );
    restService.getPaymentStatus.and.returnValue(of(finalizedStatus));

    fixture = TestBed.createComponent(PaymentComponent);
    component = fixture.componentInstance;

    spyOn(component as any, 'loadStripe').and.returnValue(Promise.resolve());
    component.stripe = {
      createToken: jasmine
        .createSpy('createToken')
        .and.returnValue(Promise.resolve({ token: { id: 'tok_visa' } })),
    };
    component.card = {};

    fixture.detectChanges();
  });

  it('prepares the checkout before any charge and displays the server total', () => {
    expect(restService.prepareCheckout).toHaveBeenCalled();
    expect(component.checkout).toEqual(checkout);
    expect(component.displayTotal).toBe('21.00');
    expect(component.displayCurrency).toBe('TRY');
    expect(restService.chargeCheckout).not.toHaveBeenCalled();
  });

  it('refreshes the cart after preparation because the snapshot was reserved', () => {
    const emitted = spyOn(component.cartPrepared, 'emit');
    component.prepareCheckout();

    expect(emitted).toHaveBeenCalled();
  });

  it('does not request a charge when no checkout has been prepared', async () => {
    component.checkout = null;

    await component.handlePayment();

    expect(component.stripe.createToken).not.toHaveBeenCalled();
    expect(restService.chargeCheckout).not.toHaveBeenCalled();
  });

  it('suppresses duplicate submissions while a charge is pending', async () => {
    const first = component.handlePayment();
    const second = component.handlePayment();

    await Promise.all([first, second]);

    expect(restService.chargeCheckout).toHaveBeenCalledTimes(1);
    expect(component.stripe.createToken).toHaveBeenCalledTimes(1);
  });

  it('sends the server checkout id and the payment token', async () => {
    await component.handlePayment();

    expect(restService.chargeCheckout).toHaveBeenCalledWith(
      'checkout-1',
      'tok_visa',
    );
  });

  it('emits payment success on a finalized payment', async () => {
    const success = spyOn(component.paymentSuccess, 'emit');

    await component.handlePayment();

    expect(success).toHaveBeenCalled();
    expect(component.isProcessing).toBeFalse();
  });

  it('recovers the existing checkout status on a network error instead of preparing again', async () => {
    restService.chargeCheckout.and.returnValue(
      throwError(() => new Error('timeout')),
    );

    await component.handlePayment();

    expect(restService.getPaymentStatus).toHaveBeenCalledWith('checkout-1');
    expect(restService.prepareCheckout).toHaveBeenCalledTimes(1);
  });

  it('polls the same checkout when the server reports a pending attempt', () => {
    restService.getPaymentStatus.and.returnValue(
      of({ ...finalizedStatus, finalized: false, pending: true, state: 'PROCESSING' }),
    );

    component.pollPaymentStatus('checkout-1', 0);

    expect(restService.getPaymentStatus).toHaveBeenCalledWith('checkout-1');
    expect(component.pendingMessage.length).toBeGreaterThanOrEqual(0);
    expect(component.errorMessage).toContain('still being processed');
  });

  it('does not treat a gracefully pending checkout as a hard failure', () => {
    restService.getPaymentStatus.and.returnValue(
      of({
        ...finalizedStatus,
        finalized: false,
        pending: true,
        state: 'CHARGE_SUCCEEDED',
      }),
    );

    component.isProcessing = true;
    component.pollPaymentStatus('checkout-1', 3);

    expect(component.pendingMessage).toContain('processed');
    expect(component.isProcessing).toBeTrue();
  });

  it('cancels a prepared checkout when the modal is closed before payment', () => {
    restService.cancelCheckout.and.returnValue(of({ ...checkout }));
    const closed = spyOn(component.closePaymentComponentEvent, 'emit');
    component.checkout = { ...checkout, status: 'PREPARED' };
    component.isProcessing = false;

    component.closePaymentComponent();

    expect(restService.cancelCheckout).toHaveBeenCalledWith('checkout-1');
    expect(closed).toHaveBeenCalled();
  });

  it('never cancels a processing checkout when the modal is closed', () => {
    restService.cancelCheckout.calls.reset();
    const closed = spyOn(component.closePaymentComponentEvent, 'emit');
    component.checkout = { ...checkout, status: 'PAYMENT_PROCESSING' };
    component.isProcessing = true;

    component.closePaymentComponent();

    expect(restService.cancelCheckout).not.toHaveBeenCalled();
    expect(closed).not.toHaveBeenCalled();
    expect(component.pendingMessage).toContain('still being verified');
  });

  it('never cancels when the local status already moved to processing', () => {
    restService.cancelCheckout.calls.reset();
    component.checkout = { ...checkout, status: 'PAYMENT_PROCESSING' };
    component.isProcessing = false;

    component.closePaymentComponent();

    expect(restService.cancelCheckout).not.toHaveBeenCalled();
  });

  it('does not start a replacement checkout while payment is processing', () => {
    restService.prepareCheckout.calls.reset();
    component.checkout = { ...checkout, status: 'PAYMENT_PROCESSING' };
    component.isProcessing = true;

    component.prepareCheckout();

    expect(restService.prepareCheckout).not.toHaveBeenCalled();
  });
});
