import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import type { Stripe } from '@stripe/stripe-js';
import { BehaviorSubject } from 'rxjs';

import { IProductData } from '../../../interfaces/IProductData';
import { IUserCartItemDto } from '../../../interfaces/IUserCartItemDto';
import { PaymentComponent } from '../components/payment/payment.component';
import { ProductBuyComponent } from '../components/product-buy/product-buy.component';
import { ProductPreviewComponent } from '../components/product-preview/product-preview.component';
import { UserCartComponent } from '../components/user-cart/user-cart.component';
import { AuthService } from '../../../services/auth/auth.service';
import { formatAmount, formatMoney } from './money-format';

/**
 * The same server-sent amount, on every surface that shows it to a customer.
 *
 * <p>This is the assertion the reported defect needed and did not have. Nothing
 * pinned the catalogue and the checkout to the same notation, so the card could
 * print `30.00 TL` and the cart `30,00 TL` for one and the same number and every
 * individual surface test still passed. Each assertion below goes through the real
 * template of a real component, because a unit test of the formatter proves the
 * formatter is right and not that anything calls it.
 *
 * <p>The amount is held at a value with a thousands separator and a half - 1234.5
 * - because a two-digit figure like `30,00` cannot tell a currency's decimal
 * separator from a whole number that happens to end in two zeros.
 */
describe('one price, one rendering, on every customer surface', () => {
  const AMOUNT = 1234.5;
  const RENDERED = '1.234,50';
  const IMAGE_BLOB = new Blob(['image-bytes'], { type: 'image/png' });

  let httpMock: HttpTestingController;
  let queryParamMap: BehaviorSubject<ReturnType<typeof convertToParamMap>>;

  function textOf(fixture: ComponentFixture<unknown>, selector: string): string {
    const element = (fixture.nativeElement as HTMLElement).querySelector(selector);
    if (!element) {
      throw new Error(`Expected "${selector}" to be rendered.`);
    }
    return (element.textContent ?? '').replace(/\s+/g, ' ').trim();
  }

  beforeEach(async () => {
    queryParamMap = new BehaviorSubject(convertToParamMap({}));

    const authStub = jasmine.createSpyObj<AuthService>('AuthService', [
      'isLoggedIn',
      'getUserMail',
    ]);
    authStub.isLoggedIn.and.returnValue(true);

    spyOn(PaymentComponent.prototype, 'loadStripe').and.resolveTo();
    spyOn(window, 'alert').and.stub();

    await TestBed.configureTestingModule({
      imports: [
        ProductPreviewComponent,
        ProductBuyComponent,
        UserCartComponent,
        PaymentComponent,
      ],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: authStub },
        {
          provide: ActivatedRoute,
          useValue: {
            queryParamMap,
            snapshot: { queryParams: {}, paramMap: convertToParamMap({}) },
            paramMap: convertToParamMap({}),
          },
        },
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.match(() => true).forEach((request) => {
      if (!request.cancelled) {
        request.flush(
          request.request.responseType === 'blob' ? IMAGE_BLOB : '',
        );
      }
    });
    httpMock.verify({ ignoreCancelled: true });
  });

  function drain<T>(fixture: ComponentFixture<T>): void {
    httpMock
      .match((request) => request.url.includes('getProductImage'))
      .forEach((request) => request.flush(IMAGE_BLOB));
    fixture.detectChanges();
  }

  describe('the product card', () => {
    it('writes the price in the storefront notation', () => {
      const fixture = TestBed.createComponent(ProductPreviewComponent);
      fixture.componentRef.setInput('productId', 11);
      fixture.componentRef.setInput('productName', 'Tam Süt');
      fixture.componentRef.setInput('productPrice', AMOUNT);
      fixture.componentRef.setInput('productCount', 4);
      fixture.componentRef.setInput('categoryId', 3);
      fixture.detectChanges();
      drain(fixture);

      expect(textOf(fixture, '.product-card__amount')).toBe(RENDERED);
      // The card splits the amount and the currency into two spans so the label
      // can be set smaller, so they are read as a pair rather than as one string.
      expect(textOf(fixture, '.product-card__currency')).toBe('TL');

      fixture.destroy();
    });

    it('writes the unit price in the same notation, with its measure intact', () => {
      const fixture = TestBed.createComponent(ProductPreviewComponent);
      fixture.componentRef.setInput('productId', 11);
      fixture.componentRef.setInput('productName', 'Tam Süt');
      fixture.componentRef.setInput('productPrice', AMOUNT);
      fixture.componentRef.setInput('productCount', 4);
      fixture.componentRef.setInput('categoryId', 3);
      fixture.componentRef.setInput('packageAmount', 1.5);
      fixture.componentRef.setInput('packageUnit', 'L');
      fixture.componentRef.setInput('unitPrice', AMOUNT);
      fixture.componentRef.setInput('unitPriceBasis', 'L');
      fixture.detectChanges();
      drain(fixture);

      // The three lines a card stacks up: a quantity and two prices, all in one
      // notation. `1.5 L` next to `1.234,50 TL/L` is the same split the review
      // found between the card and the cart.
      expect(textOf(fixture, '.product-card__package-value')).toBe('1,5 L');
      expect(textOf(fixture, '.product-card__unit-price-value')).toBe(
        `${RENDERED} TL/L`,
      );
      expect(textOf(fixture, '.product-card__amount')).toBe(RENDERED);

      fixture.destroy();
    });
  });

  describe('the product detail', () => {
    function openDetail(): ComponentFixture<ProductBuyComponent> {
      const fixture = TestBed.createComponent(ProductBuyComponent);
      fixture.componentRef.setInput('productId', 11);
      fixture.detectChanges();
      httpMock
        .expectOne((request) =>
          request.url.includes('/user/supply/getProductData'),
        )
        .flush({
          productName: 'Tam Süt',
          productPrice: AMOUNT,
          effectivePrice: AMOUNT,
          productCount: 4,
          packageAmount: 1.5,
          packageUnit: 'L',
          unitPrice: AMOUNT,
          unitPriceBasis: 'L',
        } satisfies IProductData);
      httpMock
        .expectOne((request) =>
          request.url.includes('getProductDescription'),
        )
        .flush({ descriptionList: [] });
      drain(fixture);
      return fixture;
    }

    it('quotes the server amount in the storefront notation', () => {
      const fixture = openDetail();

      expect(textOf(fixture, '.product-detail__price-amount')).toBe(RENDERED);
      expect(textOf(fixture, '.product-detail__unit-price-value')).toBe(
        `${RENDERED} TL/L`,
      );

      fixture.destroy();
    });

    /**
     * The struck-through original is a money amount too, and it used to be
     * rendered by a different function from the price beside it.
     */
    it('writes the struck-through original in the same notation as the price', () => {
      const fixture = TestBed.createComponent(ProductBuyComponent);
      fixture.componentRef.setInput('productId', 11);
      fixture.detectChanges();
      httpMock
        .expectOne((request) =>
          request.url.includes('/user/supply/getProductData'),
        )
        .flush({
          productName: 'Tam Süt',
          productPrice: 2000,
          productDiscount: 25,
          effectivePrice: AMOUNT,
          productCount: 4,
        } satisfies IProductData);
      httpMock
        .expectOne((request) =>
          request.url.includes('getProductDescription'),
        )
        .flush({ descriptionList: [] });
      drain(fixture);

      expect(textOf(fixture, '.product-detail__price-original')).toBe(
        '2.000,00 TL',
      );

      fixture.destroy();
    });
  });

  describe('the cart', () => {
    it('writes the line price and the total in the storefront notation', () => {
      const fixture = TestBed.createComponent(UserCartComponent);
      fixture.detectChanges();
      httpMock
        .expectOne((request) =>
          request.url.includes('/user/supply/getProductData'),
        )
        .flush([
          {
            productId: 10,
            productName: 'Tam Süt',
            productPrice: AMOUNT,
            productCount: 2,
            availableStock: 4,
          } satisfies IUserCartItemDto,
        ]);
      drain(fixture);

      expect(textOf(fixture, '.cart-dialog__price')).toBe(`${RENDERED} TL`);
      // The line is the unit price times the count, so the total is a different
      // number rendered by the same rule.
      expect(textOf(fixture, '[data-testid="cart-total"]')).toBe(
        `${formatAmount(AMOUNT * 2)} TL`,
      );

      fixture.destroy();
    });
  });

  describe('the payment dialog', () => {
    it('writes the server snapshot in the storefront notation', () => {
      const fixture = TestBed.createComponent(PaymentComponent);
      const component = fixture.componentInstance;
      component.stripe = {
        createToken: jasmine.createSpy('createToken'),
      } as unknown as Stripe;
      component.card = { destroy: jasmine.createSpy('destroy') } as never;
      fixture.detectChanges();
      httpMock
        .expectOne((request) => request.url.includes('/payment/checkouts'))
        .flush({
          checkoutId: 'checkout-1',
          status: 'PREPARED',
          totalAmount: AMOUNT,
          amountMinor: 123450,
          currency: 'TRY',
        });
      fixture.detectChanges();

      expect(textOf(fixture, '.payment-dialog__total-amount')).toBe(
        formatMoney(AMOUNT, 'TRY'),
      );
      expect(textOf(fixture, '.payment-dialog__total-amount')).toBe(
        `${RENDERED} TL`,
      );

      fixture.destroy();
    });
  });

  /**
   * The compact statement of the whole fix, and the one that fails if a surface
   * ever grows a private formatter again: every surface is asked for the same
   * amount, and the answers have to be the same string.
   */
  it('gives every surface the same answer for the same amount', () => {
    expect(formatAmount(AMOUNT)).toBe(RENDERED);
    expect(formatMoney(AMOUNT, 'TRY')).toBe(`${RENDERED} TL`);
  });
});
