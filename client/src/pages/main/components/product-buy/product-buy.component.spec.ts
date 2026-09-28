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
    expect(summary.textContent).toContain('50.00');
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
    expect(component.formatPrice(50)).toBe('50.00');
    expect(component.formatPrice(50.5)).toBe('50.50');
    expect(component.formatPrice(undefined)).toBe('0.00');
    expect(component.formatPrice(null)).toBe('0.00');
  });

  it('computes the discounted price from the discount percentage', () => {
    setProductId(11);
    flushProductData({ ...product, productPrice: 80, productDiscount: 25 });

    expect(component.discountedPrice).toBe(60);
  });

  it('loads the product image after the data arrives', () => {
    setProductId(11);
    flushProductData();
    flushImage();

    expect(component.productImageUrl).toBeTruthy();
  });
});
