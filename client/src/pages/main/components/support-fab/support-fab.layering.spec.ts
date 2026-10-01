import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Stripe } from '@stripe/stripe-js';

import { PaymentComponent } from '../payment/payment.component';
import { SupportFabComponent } from './support-fab.component';
import { UserCartComponent } from '../user-cart/user-cart.component';

/**
 * The launcher and the cart, as the page actually stacks them.
 *
 * <p>A host that renders the launcher as a sibling of the cart dialog, which is
 * the shape the shell produces: the launcher lives in the shell template, the
 * dialog arrives on the `modal` outlet beside it. Mounting them together is the
 * point - a z-index asserted on either component in isolation says nothing about
 * which one a press at a given point on the screen reaches.
 */
@Component({
  standalone: true,
  imports: [SupportFabComponent, UserCartComponent],
  template: `
    <app-support-fab></app-support-fab>
    <app-user-cart></app-user-cart>
  `,
})
class StackingHostComponent {}

describe('the support launcher against the cart and payment dialogs', () => {
  let fixture: ComponentFixture<StackingHostComponent>;
  let element: HTMLElement;
  let httpMock: HttpTestingController;

  /**
   * The resolved `z-index`, not the token name.
   *
   * Reading the computed value is what makes this a test of the outcome: a token
   * that is declared but not applied to the element would still pass an assertion
   * about the token.
   */
  function zIndexOf(selector: string): number {
    const node = element.querySelector(selector);
    if (!node) {
      throw new Error(`Expected "${selector}" to be rendered.`);
    }
    const value = getComputedStyle(node).zIndex;
    expect(value).not.toBe('auto');
    return Number(value);
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [StackingHostComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(StackingHostComponent);
    element = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    httpMock
      .expectOne((request) =>
        request.url.includes('/user/supply/getProductData'),
      )
      .flush([]);
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    httpMock.match(() => true).forEach((request) => {
      if (!request.cancelled) {
        request.flush(
          request.request.responseType === 'blob' ? new Blob() : '',
        );
      }
    });
    httpMock.verify({ ignoreCancelled: true });
  });

  /**
   * The reported defect, as a number. On a 390px viewport the cart fills all but
   * 8px of the height and its footer - the total and the checkout button - sits
   * within a launcher-sized box of the bottom-right corner. The launcher used to
   * declare 2100 against the cart's 1040/1050, so it painted over that button and
   * a press there reached the launcher instead.
   */
  it('stacks the launcher below the cart scrim and the cart itself', () => {
    const launcher = zIndexOf('.support-fab');
    const scrim = zIndexOf('#cart-overlay');
    const cart = zIndexOf('#cart-container');

    expect(launcher).toBeLessThan(scrim);
    expect(launcher).toBeLessThan(cart);
    // The scrim still covers the launcher, so a press in that corner is the
    // dialog's to answer rather than the launcher's.
    expect(scrim).toBeLessThan(cart);
  });

  it('keeps the payment dialog above both', () => {
    const payment = TestBed.createComponent(PaymentComponent);
    const component = payment.componentInstance;
    component.stripe = {
      createToken: jasmine.createSpy('createToken'),
    } as unknown as Stripe;
    component.card = { destroy: jasmine.createSpy('destroy') } as never;
    payment.detectChanges();
    httpMock
      .expectOne((request) => request.url.includes('/payment/checkouts'))
      .flush({
        checkoutId: 'checkout-1',
        status: 'PREPARED',
        totalAmount: 100,
        amountMinor: 10000,
        currency: 'TRY',
      });
    payment.detectChanges();

    const backdrop = (payment.nativeElement as HTMLElement).querySelector(
      '#payment-component-bg',
    ) as HTMLElement;
    const dialog = (payment.nativeElement as HTMLElement).querySelector(
      '#payment-dialog',
    ) as HTMLElement;

    expect(Number(getComputedStyle(backdrop).zIndex)).toBeGreaterThan(
      zIndexOf('#cart-container'),
    );
    expect(Number(getComputedStyle(dialog).zIndex)).toBeGreaterThan(
      Number(getComputedStyle(backdrop).zIndex),
    );

    payment.destroy();
  });

  /**
   * Every storefront layer names one of the declared rungs.
   *
   * A ladder is only a convention until a component opts into it, and the way it
   * stops being one is a fourth unlayered number appearing in a stylesheet. The
   * rungs are read from the document rather than restated here, so this fails when
   * a rung is renamed and passes only when every layer really resolves to one.
   */
  it('resolves every layer to a declared rung', () => {
    const root = getComputedStyle(document.documentElement);
    const rungs = [
      '--layer-floating',
      '--layer-sticky-header',
      '--layer-modal-scrim',
      '--layer-modal',
      '--layer-modal-scrim-raised',
      '--layer-modal-raised',
    ].map((name) => Number(root.getPropertyValue(name).trim()));

    for (const rung of rungs) {
      expect(Number.isFinite(rung)).toBeTrue();
    }
    // Strictly increasing: a duplicate rung is two layers that can only be
    // separated by document order, which is the accident this ladder removes.
    for (let index = 1; index < rungs.length; index += 1) {
      expect(rungs[index]).toBeGreaterThan(rungs[index - 1]);
    }

    expect(zIndexOf('.support-fab')).toBe(rungs[0]);
    expect(zIndexOf('#cart-overlay')).toBe(rungs[2]);
    expect(zIndexOf('#cart-container')).toBe(rungs[3]);
  });
});
