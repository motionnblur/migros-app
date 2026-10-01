import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';

import { AuthService } from '../../../../services/auth/auth.service';
import { ProductBuyComponent } from './product-buy.component';

describe('ProductBuyComponent', () => {
  let component: ProductBuyComponent;
  let fixture: ComponentFixture<ProductBuyComponent>;
  let httpMock: HttpTestingController;
  let authStub: jasmine.SpyObj<AuthService>;
  let navigateSpy: jasmine.Spy;

  const IMAGE_BLOB = new Blob(['image-bytes'], { type: 'image/png' });

  const product = {
    productName: 'Tam Süt',
    subCategoryName: 'Süt',
    productPrice: 50,
    productCount: 4,
    productDiscount: 0,
    productCategoryId: 3,
  };

  const descriptions = {
    productId: 11,
    descriptionList: [
      { descriptionId: 1, descriptionTabName: 'Özellikler', descriptionTabContent: '<p>Bir</p>' },
      { descriptionId: 2, descriptionTabName: 'Kullanım', descriptionTabContent: '<p>İki</p>' },
    ],
  };

  const unsafeDescription =
    '<p onclick="steal()">Merhaba <em>dünya</em></p>' +
    '<a href="javascript:steal()">bağlantı</a>';

  function descriptionPanel(): HTMLElement {
    return fixture.nativeElement.querySelector('.product-description-content');
  }

  function tabButtons(): HTMLButtonElement[] {
    return Array.from(
      fixture.nativeElement.querySelectorAll(
        '[role="tab"]',
      ) as NodeListOf<HTMLButtonElement>,
    );
  }

  function descriptionElement(selector: string): HTMLElement {
    const element = descriptionPanel().querySelector(selector) as HTMLElement | null;
    if (!element) {
      throw new Error(`Expected "${selector}" inside the description panel.`);
    }
    return element;
  }

  function setProductId(productId: number): void {
    fixture.componentRef.setInput('productId', productId);
    fixture.detectChanges();
  }

  function requests(fragment: string): TestRequest[] {
    return httpMock.match((request) => request.url.includes(fragment));
  }

  function expectRequest(fragment: string): TestRequest {
    return httpMock.expectOne((request) => request.url.includes(fragment));
  }

  function flushProductData(payload: object = product): void {
    requests('getProductDataWithProductId')[0].flush(payload);
    fixture.detectChanges();
  }

  function flushDescriptions(payload: object = descriptions): void {
    requests('getProductDescription')[0].flush(payload);
    fixture.detectChanges();
  }

  function flushImage(): void {
    requests('getProductImage').forEach((request) => request.flush(IMAGE_BLOB));
    fixture.detectChanges();
  }

  beforeEach(async () => {
    authStub = jasmine.createSpyObj<AuthService>('AuthService', [
      'isLoggedIn',
      'getUserMail',
    ]);
    authStub.isLoggedIn.and.returnValue(true);

    await TestBed.configureTestingModule({
      imports: [ProductBuyComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: authStub },
        {
          provide: ActivatedRoute,
          useValue: { queryParamMap: null, snapshot: { queryParams: {} } },
        },
      ],
    }).compileComponents();

    navigateSpy = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    httpMock = TestBed.inject(HttpTestingController);

    fixture = TestBed.createComponent(ProductBuyComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    fixture.destroy();
    httpMock.match(() => true).forEach((request) => {
      if (request.cancelled) {
        return;
      }
      request.flush(
        request.request.responseType === 'blob' ? IMAGE_BLOB : (null as never),
      );
    });
    httpMock.verify({ ignoreCancelled: true });
  });

  it('creates', () => {
    setProductId(11);
    expect(component).toBeTruthy();
  });

  it('shows a loading state until the product data arrives', () => {
    setProductId(11);

    expect(component.isLoading).toBeTrue();
    expect(
      fixture.nativeElement.querySelector('.product-detail__state[role="status"]'),
    ).toBeTruthy();

    flushProductData();
    expect(component.isLoading).toBeFalse();
  });

  it('shows a retryable error state and reloads on retry', () => {
    setProductId(11);
    requests('getProductDataWithProductId')[0].flush('boom', {
      status: 500,
      statusText: 'Server Error',
    });
    fixture.detectChanges();

    expect(component.hasLoadError).toBeTrue();
    const alert = fixture.nativeElement.querySelector(
      '.product-detail__state--error',
    ) as HTMLElement;
    expect(alert.getAttribute('role')).toBe('alert');

    (fixture.nativeElement.querySelector('.product-detail__retry') as HTMLButtonElement)
      .click();
    fixture.detectChanges();

    expect(component.hasLoadError).toBeFalse();
    expect(requests('getProductDataWithProductId').length).toBe(1);
  });

  it('renders name, price, stock and the add action in that order', () => {
    setProductId(11);
    flushProductData();
    flushDescriptions();

    const summary = fixture.nativeElement.querySelector(
      '.product-detail__summary',
    ) as HTMLElement;
    const classes = Array.from(summary.children).map((child) => child.className);

    expect(classes[0]).toContain('product-detail__name');
    expect(classes[1]).toContain('product-detail__price');
    expect(classes[2]).toContain('product-detail__stock');
    expect(classes[3]).toContain('product-detail__action');
    expect(summary.textContent).toContain('Tam Süt');
    expect(summary.textContent).toContain('50,00');
  });

  it('labels the stock line as Stok and never mentions Miktar', () => {
    setProductId(11);
    flushProductData();
    flushDescriptions();

    const stock = fixture.nativeElement.querySelector('.product-detail__stock');
    expect(stock.textContent).toContain('Stok');
    expect(stock.textContent).toContain('4 adet');
    expect(fixture.nativeElement.textContent).not.toContain('Miktar');
  });

  it('offers no quantity selector', () => {
    setProductId(11);
    flushProductData();
    flushDescriptions();

    expect(fixture.nativeElement.querySelector('input[type="number"]')).toBeNull();
  });

  it('removes the static campaign image from the purchase area', () => {
    setProductId(11);
    flushProductData();
    flushDescriptions();

    const images = Array.from(
      fixture.nativeElement.querySelectorAll(
        '.product-detail__purchase img',
      ) as NodeListOf<HTMLImageElement>,
    );
    expect(images.length).toBe(1);
    expect(images[0].getAttribute('src')).not.toContain('moneyy');
  });

  it('disables the add action and reports the empty stock for out of stock products', () => {
    setProductId(11);
    flushProductData({ ...product, productCount: 0 });
    flushDescriptions();

    const button = fixture.nativeElement.querySelector(
      '.product-detail__add',
    ) as HTMLButtonElement;
    expect(button.disabled).toBeTrue();
    expect(fixture.nativeElement.textContent).toContain('Tükendi');
  });

  it('reports inline success feedback instead of an alert after adding to the cart', () => {
    setProductId(11);
    flushProductData();
    flushDescriptions();

    (fixture.nativeElement.querySelector('.product-detail__add') as HTMLButtonElement)
      .click();

    const cartRequests = requests('addProductToUserCart');
    expect(cartRequests.length).toBe(1);
    cartRequests[0].flush('');
    fixture.detectChanges();

    const feedback = fixture.nativeElement.querySelector(
      '.product-detail__feedback',
    ) as HTMLElement;
    expect(feedback.textContent).toContain('Ürün sepete eklendi.');
    expect(feedback.getAttribute('role')).toBe('status');
  });

  it('reports inline error feedback when the add request fails', () => {
    setProductId(11);
    flushProductData();
    flushDescriptions();

    (fixture.nativeElement.querySelector('.product-detail__add') as HTMLButtonElement)
      .click();
    requests('addProductToUserCart')[0].flush('You cannot add more than available stock.', {
      status: 400,
      statusText: 'Bad Request',
    });
    fixture.detectChanges();

    const feedback = fixture.nativeElement.querySelector(
      '.product-detail__feedback',
    ) as HTMLElement;
    expect(feedback.textContent).toContain('You cannot add more than available stock.');
    expect(feedback.getAttribute('role')).toBe('alert');
  });

  it('opens the login modal without a request when the visitor is signed out', () => {
    authStub.isLoggedIn.and.returnValue(false);
    setProductId(11);
    flushProductData();
    flushDescriptions();

    (fixture.nativeElement.querySelector('.product-detail__add') as HTMLButtonElement)
      .click();

    expect(requests('addProductToUserCart').length).toBe(0);
    expect(navigateSpy).toHaveBeenCalledWith(
      [{ outlets: { modal: ['login'] } }],
      jasmine.any(Object),
    );
  });

  it('exposes the description tabs with a keyboard operable tablist', () => {
    setProductId(11);
    flushProductData();
    flushDescriptions();

    const tablist = fixture.nativeElement.querySelector('[role="tablist"]');
    const tabs = Array.from(
      fixture.nativeElement.querySelectorAll('[role="tab"]') as NodeListOf<HTMLElement>,
    );

    expect(tablist).toBeTruthy();
    expect(tabs.length).toBe(2);
    expect(tabs[0].getAttribute('aria-selected')).toBe('true');
    expect(tabs[0].getAttribute('tabindex')).toBe('0');
    expect(tabs[1].getAttribute('aria-selected')).toBe('false');
    expect(tabs[1].getAttribute('tabindex')).toBe('-1');
    expect(
      fixture.nativeElement
        .querySelector('[role="tabpanel"]')
        .getAttribute('aria-labelledby'),
    ).toBe(tabs[0].getAttribute('id'));
  });

  it('switches tabs with the arrow keys', () => {
    setProductId(11);
    flushProductData();
    flushDescriptions();

    const tabs = fixture.nativeElement.querySelectorAll(
      '[role="tab"]',
    ) as NodeListOf<HTMLButtonElement>;

    tabs[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    fixture.detectChanges();
    expect(component.selectedTabIndex).toBe(1);

    tabs[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
    fixture.detectChanges();
    expect(component.selectedTabIndex).toBe(0);
  });

  it('keeps the product visible when only the descriptions fail', () => {
    setProductId(11);
    flushProductData();
    requests('getProductDescription')[0].flush('nope', {
      status: 500,
      statusText: 'Server Error',
    });
    fixture.detectChanges();

    expect(component.hasLoadError).toBeFalse();
    expect(fixture.nativeElement.querySelector('.product-detail__name').textContent).toContain(
      'Tam Süt',
    );
    expect(fixture.nativeElement.querySelector('[role="tablist"]')).toBeNull();
  });

  it('reloads when the routed product id changes', () => {
    setProductId(11);
    flushProductData();
    flushDescriptions();

    setProductId(12);
    expect(component.isLoading).toBeTrue();

    flushProductData({ ...product, productName: 'Yoğurt' });
    flushDescriptions();
    expect(fixture.nativeElement.querySelector('.product-detail__name').textContent).toContain(
      'Yoğurt',
    );
  });

  it('emits the loaded product name for the breadcrumb', () => {
    const names: string[] = [];
    component.productNameChange.subscribe((name) => names.push(name));

    setProductId(11);
    flushProductData();

    expect(names).toEqual(['Tam Süt']);
  });

  it('falls back to a placeholder image when the product image fails', () => {
    setProductId(11);
    flushProductData();
    expectRequest('getProductImage').flush(IMAGE_BLOB, {
      status: 404,
      statusText: 'Not Found',
    });
    fixture.detectChanges();

    const image = fixture.nativeElement.querySelector(
      '.product-detail__image',
    ) as HTMLImageElement;
    expect(image.getAttribute('src')).toContain('data:image/svg+xml');
  });

  it('formats prices with two decimals', () => {
    expect(component.formatPrice(50)).toBe('50,00');
    expect(component.formatPrice(50.5)).toBe('50,50');
    expect(component.formatPrice(undefined)).toBe('0,00');
    expect(component.formatPrice(null)).toBe('0,00');
  });

  it('computes the discounted price from the discount percentage', () => {
    setProductId(11);
    flushProductData({ ...product, productPrice: 80, productDiscount: 25 });

    expect(component.discountedPrice).toBe(60);
  });

  it('quotes the server effective price instead of recomputing it in the browser', () => {
    setProductId(11);
    // 10.10 at 5 percent off is 9.60 under ProductPricingPolicy and 9.59 under
    // +(10.10 - 10.10 * 5 / 100).toFixed(2). The card, the cart line and the charge
    // are all 9.60, so the detail page has to be too.
    flushProductData({
      ...product,
      productPrice: 10.1,
      productDiscount: 5,
      effectivePrice: 9.6,
    });

    expect(component.discountedPrice).toBe(9.6);
  });

  it('renders the server effective price in the detail template', () => {
    setProductId(11);
    flushProductData({
      ...product,
      productPrice: 10.1,
      productDiscount: 5,
      effectivePrice: 9.6,
    });
    fixture.detectChanges();

    const amount = fixture.nativeElement.querySelector(
      '.product-detail__price-amount',
    ) as HTMLElement;
    expect(amount.textContent?.trim()).toBe('9,60');
  });

  it('loads the product image after the data arrives', () => {
    setProductId(11);
    flushProductData();
    flushImage();

    expect(component.productImageUrl).toBeTruthy();
  });

  it('sanitizes the first description tab instead of trusting the stored HTML', () => {
    setProductId(11);
    flushProductData();
    flushDescriptions({
      productId: 11,
      descriptionList: [
        { descriptionId: 1, descriptionTabName: 'Özellikler', descriptionTabContent: unsafeDescription },
        { descriptionId: 2, descriptionTabName: 'Kullanım', descriptionTabContent: '<p>İki</p>' },
      ],
    });

    const panel = descriptionPanel();
    expect(component.selectedTabIndex).toBe(0);
    expect(descriptionElement('em').textContent).toContain('dünya');
    expect(panel.textContent).toContain('Merhaba');
    expect(descriptionElement('p').hasAttribute('onclick')).toBeFalse();
    expect(descriptionElement('a').getAttribute('href')).not.toMatch(/^javascript:/i);
  });

  it('sanitizes a description tab selected after the first one', () => {
    setProductId(11);
    flushProductData();
    flushDescriptions({
      productId: 11,
      descriptionList: [
        { descriptionId: 1, descriptionTabName: 'Özellikler', descriptionTabContent: '<p>Bir</p>' },
        { descriptionId: 2, descriptionTabName: 'Kullanım', descriptionTabContent: unsafeDescription },
      ],
    });

    tabButtons()[1].click();
    fixture.detectChanges();

    const panel = descriptionPanel();
    expect(component.selectedTabIndex).toBe(1);
    expect(descriptionElement('em').textContent).toContain('dünya');
    expect(panel.textContent).toContain('Merhaba');
    expect(descriptionElement('p').hasAttribute('onclick')).toBeFalse();
    expect(descriptionElement('a').getAttribute('href')).not.toMatch(/^javascript:/i);
  });

  it('keeps tab keyboard navigation and the empty-description behaviour', () => {
    setProductId(11);
    flushProductData();
    flushDescriptions({
      productId: 11,
      descriptionList: [
        { descriptionId: 1, descriptionTabName: 'Özellikler', descriptionTabContent: '<p><em>Bir</em></p>' },
        { descriptionId: 2, descriptionTabName: 'Kullanım', descriptionTabContent: '<p><strong>İki</strong></p>' },
      ],
    });

    const tabs = tabButtons();
    tabs[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    fixture.detectChanges();

    expect(component.selectedTabIndex).toBe(1);
    expect(descriptionElement('strong').textContent).toContain('İki');

    tabs[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }));
    fixture.detectChanges();
    expect(component.selectedTabIndex).toBe(1);

    tabs[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
    fixture.detectChanges();
    expect(component.selectedTabIndex).toBe(0);
    expect(descriptionElement('em').textContent).toContain('Bir');

    setProductId(12);
    flushProductData({ ...product, productName: 'Yoğurt' });
    flushDescriptions({ productId: 12, descriptionList: [] });
    expect(fixture.nativeElement.querySelector('[role="tablist"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('.product-detail__name').textContent).toContain(
      'Yoğurt',
    );
  });

  it('cancels the previous product requests instead of showing their late response', () => {
    setProductId(11);
    const staleData = requests('getProductDataWithProductId')[0];

    setProductId(12);

    expect(staleData.cancelled).toBeTrue();
    expect(component.isLoading).toBeTrue();
    expect(fixture.nativeElement.querySelector('[role="tablist"]')).toBeNull();

    flushProductData({ ...product, productName: 'Yoğurt' });
    flushDescriptions();
    expect(fixture.nativeElement.querySelector('.product-detail__name').textContent).toContain(
      'Yoğurt',
    );
  });

  it('revokes the previous object URL and shows the placeholder until the new image arrives', () => {
    const revokeSpy = spyOn(URL, 'revokeObjectURL').and.callThrough();

    setProductId(11);
    flushProductData();
    flushImage();
    const firstImageUrl = component.productImageUrl as string;
    expect(firstImageUrl).toBeTruthy();

    setProductId(12);

    expect(component.productImageUrl).toBeNull();
    expect(revokeSpy).toHaveBeenCalledWith(firstImageUrl);

    flushProductData({ ...product, productName: 'Yoğurt' });
    expect(
      (fixture.nativeElement.querySelector('.product-detail__image') as HTMLImageElement)
        .getAttribute('src'),
    ).toContain('data:image/svg+xml');

    flushImage();
    const image = fixture.nativeElement.querySelector(
      '.product-detail__image',
    ) as HTMLImageElement;
    expect(image.getAttribute('src')).toBe(component.productImageUrl);
    expect(component.productImageUrl).not.toBe(firstImageUrl);
  });

  it('never brings the previous product name or image back when the new ones fail', () => {
    const emittedNames: string[] = [];
    component.productNameChange.subscribe((name) => emittedNames.push(name));

    setProductId(11);
    flushProductData();
    flushImage();
    const firstImageUrl = component.productImageUrl;

    setProductId(12);
    requests('getProductDataWithProductId')[0].flush('boom', {
      status: 500,
      statusText: 'Server Error',
    });
    fixture.detectChanges();

    expect(component.productImageUrl).toBeNull();
    expect(component.productImageUrl).not.toBe(firstImageUrl);
    expect(emittedNames).toEqual(['Tam Süt']);
    expect(fixture.nativeElement.querySelector('.product-detail__name')).toBeNull();
  });

  it('drops a pending add-to-cart when the product changes', () => {
    setProductId(11);
    flushProductData();
    flushDescriptions();
    flushImage();

    (fixture.nativeElement.querySelector('.product-detail__add') as HTMLButtonElement)
      .click();
    const cartRequest = requests('addProductToUserCart')[0];
    expect(cartRequest).toBeTruthy();

    setProductId(12);

    expect(cartRequest.cancelled).toBeTrue();
    expect(component.isAddingToCart).toBeFalse();
    expect(fixture.nativeElement.querySelector('.product-detail__feedback')).toBeNull();
  });

  /**
   * Package size and unit price, and the rule underneath both of them: an absent
   * fact renders as nothing at all. There is no placeholder, no dash and no value
   * inferred from the product name, because a guessed size produces a unit price
   * that looks authoritative and is wrong - and most of the catalogue has no size
   * at all, since the columns were added without a backfill.
   */
  describe('package size and unit price', () => {
    /**
     * Returns the element, or throws when it is absent - so a test that means to
     * assert on a rendered line fails with "expected this element" rather than on a
     * null dereference somewhere further down.
     */
    function detail(selector: string): HTMLElement {
      const element = fixture.nativeElement.querySelector(selector);
      if (!element) {
        throw new Error(`Expected "${selector}" to be rendered.`);
      }
      return element as HTMLElement;
    }

    function rendered(selector: string): boolean {
      return fixture.nativeElement.querySelector(selector) !== null;
    }

    function textOf(selector: string): string {
      return (detail(selector).textContent ?? '').trim();
    }

    it('shows nothing for a product with no package metadata', () => {
      setProductId(11);
      flushProductData();
      flushDescriptions();

      expect(rendered('.product-detail__package')).toBeFalse();
      expect(rendered('.product-detail__unit-price')).toBeFalse();
      expect(fixture.nativeElement.textContent).not.toContain('Birim fiyat');
    });

    it('shows nothing when only the amount arrives without a unit', () => {
      setProductId(11);
      flushProductData({ ...product, packageAmount: 500 });
      flushDescriptions();

      expect(rendered('.product-detail__package')).toBeFalse();
    });

    it('shows the size and the unit price the server computed', () => {
      setProductId(11);
      flushProductData({
        ...product,
        packageAmount: 0.75,
        packageUnit: 'KG',
        unitPrice: 66.65,
        unitPriceBasis: 'KG',
      });
      flushDescriptions();

      expect(detail('.product-detail__package').textContent).toContain(
        '0,75 KG',
      );
      expect(detail('.product-detail__unit-price').textContent).toContain(
        '66,65 TL/kg',
      );
    });

    /**
     * The whole point of the feature is comparing a 500 g pack with a 1 kg pack, so
     * a size with three decimals has to survive to the screen. `0.5` for a stored
     * `0.500` would misreport what the customer is buying.
     */
    it('keeps the precision the amount was stored with', () => {
      setProductId(11);
      flushProductData({
        ...product,
        packageAmount: 0.125,
        packageUnit: 'KG',
        unitPrice: 400,
        unitPriceBasis: 'KG',
      });
      flushDescriptions();

      expect(textOf('.product-detail__package-value')).toBe(
        '0,125 KG',
      );
    });

    it('shows the size even when the basis cannot be priced', () => {
      setProductId(11);
      flushProductData({
        ...product,
        packageAmount: 500,
        packageUnit: 'GRAM',
        unitPrice: null,
        unitPriceBasis: null,
      });
      flushDescriptions();

      expect(textOf('.product-detail__package-value')).toBe(
        '500 GRAM',
      );
      // A unit price with no basis reads as a second package price.
      expect(rendered('.product-detail__unit-price')).toBeFalse();
    });

    it('never divides on the client', () => {
      setProductId(11);
      flushProductData({
        ...product,
        packageAmount: 3,
        packageUnit: 'ADET',
        unitPrice: 16.67,
        unitPriceBasis: 'ADET',
      });
      flushDescriptions();

      expect(textOf('.product-detail__unit-price-value')).toBe(
        '16,67 TL/adet',
      );
    });

    /**
     * A price with no measure beside a package price looks like a second package
     * price, so an unrecognized basis hides the whole line rather than printing the
     * number alone.
     */
    it('hides the unit price when the basis names nothing this client knows', () => {
      setProductId(11);
      flushProductData({
        ...product,
        packageAmount: 1,
        packageUnit: 'L',
        unitPrice: 50,
        unitPriceBasis: 'PER_SHELF',
      });
      flushDescriptions();

      expect(rendered('.product-detail__unit-price')).toBeFalse();
    });

    /**
     * The package price is what the customer pays and what the add button buys. The
     * unit price is the comparison aid beneath it, so the price line has to stay
     * the first thing in the summary and the unit price has to come after it.
     */
    it('keeps the package price the dominant number', () => {
      setProductId(11);
      flushProductData({
        ...product,
        packageAmount: 0.75,
        packageUnit: 'KG',
        unitPrice: 66.65,
        unitPriceBasis: 'KG',
      });
      flushDescriptions();

      const summary = fixture.nativeElement.querySelector(
        '.product-detail__summary',
      ) as HTMLElement;
      const classes = Array.from(summary.children).map((child) => child.className);

      expect(classes[0]).toContain('product-detail__name');
      expect(classes[1]).toContain('product-detail__price');
      expect(classes[2]).toContain('product-detail__package');
      expect(classes[3]).toContain('product-detail__unit-price');
      expect(detail('.product-detail__price-amount').textContent?.trim()).toBe('50,00');
    });
  });
});
