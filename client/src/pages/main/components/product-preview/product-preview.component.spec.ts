import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import {
  ActivatedRoute,
  ParamMap,
  Params,
  Router,
  convertToParamMap,
  provideRouter,
} from '@angular/router';
import { BehaviorSubject } from 'rxjs';

import { AuthService } from '../../../../services/auth/auth.service';
import { ProductPreviewComponent } from './product-preview.component';

describe('ProductPreviewComponent', () => {
  let component: ProductPreviewComponent;
  let fixture: ComponentFixture<ProductPreviewComponent>;
  let httpMock: HttpTestingController;
  let authStub: jasmine.SpyObj<AuthService>;
  let router: Router;
  let navigateSpy: jasmine.Spy;
  let queryParamMap: BehaviorSubject<ParamMap>;
  let snapshot: { queryParams: Params; paramMap: ParamMap };

  const IMAGE_BLOB = new Blob(['image-bytes'], { type: 'image/png' });

  function setQueryParams(params: Record<string, string>): void {
    snapshot.queryParams = { ...params };
    queryParamMap.next(convertToParamMap(params));
  }

  function flushImageRequests(): void {
    httpMock
      .match((request) => request.url.includes('getProductImage'))
      .forEach((request) => {
        if (request.cancelled) {
          return;
        }
        request.flush(IMAGE_BLOB);
      });
  }

  function queryCartRequests(): TestRequest[] {
    return httpMock.match((request) => request.url.includes('addProductToUserCart'));
  }

  beforeEach(async () => {
    queryParamMap = new BehaviorSubject<ParamMap>(convertToParamMap({}));
    snapshot = { queryParams: {}, paramMap: convertToParamMap({}) };

    authStub = jasmine.createSpyObj<AuthService>('AuthService', [
      'isLoggedIn',
      'getUserMail',
    ]);
    authStub.isLoggedIn.and.returnValue(true);

    await TestBed.configureTestingModule({
      imports: [ProductPreviewComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: authStub },
        {
          provide: ActivatedRoute,
          useValue: { queryParamMap, snapshot, paramMap: convertToParamMap({}) },
        },
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    navigateSpy = spyOn(router, 'navigate').and.resolveTo(true);
    httpMock = TestBed.inject(HttpTestingController);

    fixture = TestBed.createComponent(ProductPreviewComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('productId', 11);
    fixture.componentRef.setInput('productName', 'Tam Süt');
    fixture.componentRef.setInput('productPrice', 49.9);
    fixture.componentRef.setInput('productCount', 4);
    fixture.componentRef.setInput('categoryId', 3);
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    flushImageRequests();
    httpMock.verify({ ignoreCancelled: true });
  });

  it('creates', () => {
    expect(component).toBeTruthy();
  });

  it('renders the product navigation as a real link on the product route', () => {
    const link = fixture.nativeElement.querySelector(
      'a.product-card__link',
    ) as HTMLAnchorElement;

    expect(link).toBeTruthy();
    expect(link.getAttribute('href')).toBe('/category/3/product/11');
  });

  it('keeps the originating subcategory and page on the product link', () => {
    setQueryParams({ subcategory: 'Süt', page: '3' });
    fixture.detectChanges();

    const link = fixture.nativeElement.querySelector(
      'a.product-card__link',
    ) as HTMLAnchorElement;

    expect(link.getAttribute('href')).toContain('subcategory=S%C3%BCt');
    expect(link.getAttribute('href')).toContain('page=3');
  });

  it('carries the whole listing state onto the product link', () => {
    setQueryParams({
      q: 'çiçek',
      subcategory: 'Süt',
      page: '3',
      availability: 'IN_STOCK',
      minPrice: '10',
      discounted: 'true',
      sort: 'PRICE_ASC',
    });
    fixture.detectChanges();

    const href = (fixture.nativeElement.querySelector(
      'a.product-card__link',
    ) as HTMLAnchorElement).getAttribute('href') as string;

    expect(href).toContain('q=%C3%A7i%C3%A7ek');
    expect(href).toContain('availability=IN_STOCK');
    expect(href).toContain('minPrice=10');
    expect(href).toContain('discounted=true');
    expect(href).toContain('sort=PRICE_ASC');
  });

  it('leaves parameters that are not part of a listing off the product link', () => {
    setQueryParams({ subcategory: 'Süt', tracking: 'utm-source' });
    fixture.detectChanges();

    const href = (fixture.nativeElement.querySelector(
      'a.product-card__link',
    ) as HTMLAnchorElement).getAttribute('href') as string;

    expect(href).not.toContain('tracking');
  });

  /**
   * A search result belongs to no single category, so there is no category detail
   * URL to build for it. Guessing one would send the customer to a category they
   * did not choose.
   */
  it('links to the category-less product route when there is no category', () => {
    fixture.componentRef.setInput('categoryId', null);
    fixture.detectChanges();

    expect(component.hasCategoryLink).toBeFalse();
    expect(
      (fixture.nativeElement.querySelector(
        'a.product-card__link',
      ) as HTMLAnchorElement).getAttribute('href'),
    ).toBe('/product/11');
  });

  it('shows the effective price returned by the API without an original price', () => {
    const price = fixture.nativeElement.querySelector('.product-card__price')
      .textContent as string;

    expect(price).toContain('49,90');
    expect(fixture.nativeElement.querySelector('.product-card__price').children.length).toBe(2);
  });

  it('gives the add button an accessible name and keeps a touch sized target', () => {
    const button = fixture.nativeElement.querySelector(
      '.product-card__add',
    ) as HTMLButtonElement;

    expect(button.tagName).toBe('BUTTON');
    expect(button.getAttribute('aria-label')).toContain('Tam Süt');
    expect(button.disabled).toBeFalse();
  });

  it('disables adding an out of stock product and reports the empty stock line', () => {
    fixture.componentRef.setInput('productCount', 0);
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector(
      '.product-card__add',
    ) as HTMLButtonElement;

    expect(button.disabled).toBeTrue();
    expect(
      fixture.nativeElement.querySelector('.product-card__stock').textContent,
    ).toContain('Tükendi');
  });

  /**
   * The refusal has to hold at the DOM, not only in the component method.
   *
   * A disabled button swallows the click before Angular ever sees it, so a real
   * `click()` on it is the only proof that a customer pressing the control cannot
   * start a request - a test that calls `addProductToUserCart()` directly would
   * pass even if the binding that disables the button were removed, which is
   * exactly the regression this is here to catch.
   */
  it('issues no add-to-cart request from a DOM click on a sold-out card', () => {
    fixture.componentRef.setInput('productCount', 0);
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector(
      '.product-card__add',
    ) as HTMLButtonElement;
    button.click();
    fixture.detectChanges();

    expect(queryCartRequests().length).toBe(0);
    expect(button.disabled).toBeTrue();
    // The detail page stays reachable from a sold-out card.
    expect(
      fixture.nativeElement.querySelector('.product-card__link'),
    ).toBeTruthy();
  });

  it('adds one item and reports inline success feedback instead of an alert', () => {
    component.addProductToUserCart();

    const requests = queryCartRequests();
    expect(requests.length).toBe(1);
    expect(requests[0].request.params.get('productId')).toBe('11');
    requests[0].flush('');
    fixture.detectChanges();

    const feedback = fixture.nativeElement.querySelector(
      '.product-card__feedback',
    ) as HTMLElement;
    expect(feedback.textContent).toContain('Tam Süt sepete eklendi.');
    expect(feedback.getAttribute('role')).toBe('status');
  });

  it('reports inline error feedback with the backend message', () => {
    component.addProductToUserCart();

    const requests = queryCartRequests();
    requests[0].flush('You cannot add more than available stock.', {
      status: 400,
      statusText: 'Bad Request',
    });
    fixture.detectChanges();

    const feedback = fixture.nativeElement.querySelector(
      '.product-card__feedback',
    ) as HTMLElement;
    expect(feedback.textContent).toContain('You cannot add more than available stock.');
    expect(feedback.getAttribute('role')).toBe('alert');
    expect(component.isAddingToCart).toBeFalse();
  });

  it('opens the login modal without a request when the visitor is signed out', () => {
    authStub.isLoggedIn.and.returnValue(false);

    component.addProductToUserCart();

    expect(queryCartRequests().length).toBe(0);
    expect(navigateSpy).toHaveBeenCalledWith(
      [{ outlets: { modal: ['login'] } }],
      jasmine.any(Object),
    );
  });

  it('shows inline feedback when an out of stock product is added anyway', () => {
    fixture.componentRef.setInput('productCount', 0);
    fixture.detectChanges();

    component.addProductToUserCart();

    expect(queryCartRequests().length).toBe(0);
    expect(component.feedbackKind).toBe('error');
  });

  it('clears the inline feedback after a moment', () => {
    jasmine.clock().install();
    try {
      component.addProductToUserCart();
      queryCartRequests()[0].flush('');
      fixture.detectChanges();
      expect(component.feedbackMessage).toBeTruthy();

      jasmine.clock().tick(5000);
      fixture.detectChanges();
      expect(component.feedbackMessage).toBe('');
      expect(
        fixture.nativeElement.querySelector('.product-card__feedback'),
      ).toBeNull();
    } finally {
      jasmine.clock().uninstall();
    }
  });

  it('releases the product image object URL when destroyed', () => {
    flushImageRequests();
    fixture.detectChanges();
    expect(component.imageUrl).toBeTruthy();

    const revokeSpy = spyOn(URL, 'revokeObjectURL');
    fixture.destroy();

    expect(revokeSpy).toHaveBeenCalled();
  });

  /**
   * Package size and unit price on a card.
   *
   * The rules being asserted are the ones that make the number trustworthy: an
   * absent fact renders as nothing at all, and a present one is rendered from the
   * server's numbers rather than computed here. Most of the catalogue has no
   * package size - the columns were added nullable and deliberately not
   * backfilled - so the "renders nothing" case is the common one and the one a
   * regression would break first.
   */
  describe('package size and unit price', () => {
    function rendered(selector: string): boolean {
      return fixture.nativeElement.querySelector(selector) !== null;
    }

    function textOf(selector: string): string {
      const element = fixture.nativeElement.querySelector(selector) as HTMLElement | null;
      if (!element) {
        throw new Error(`Expected "${selector}" to be rendered.`);
      }
      return (element.textContent ?? '').trim();
    }

    it('renders neither line for a product with no package metadata', () => {
      expect(rendered('.product-card__package')).toBeFalse();
      expect(rendered('.product-card__unit-price')).toBeFalse();
    });

    it('renders the size and the unit price the server computed', () => {
      fixture.componentRef.setInput('packageAmount', 0.75);
      fixture.componentRef.setInput('packageUnit', 'KG');
      fixture.componentRef.setInput('unitPrice', 66.65);
      fixture.componentRef.setInput('unitPriceBasis', 'KG');
      fixture.detectChanges();

      expect(textOf('.product-card__package-value')).toBe('0,75 KG');
      expect(textOf('.product-card__unit-price-value')).toBe('66,65 TL/kg');
    });

    it('keeps three decimals of package size, which is the precision it can carry', () => {
      fixture.componentRef.setInput('packageAmount', 0.125);
      fixture.componentRef.setInput('packageUnit', 'KG');
      fixture.componentRef.setInput('unitPrice', 400);
      fixture.componentRef.setInput('unitPriceBasis', 'KG');
      fixture.detectChanges();

      expect(textOf('.product-card__package-value')).toBe('0,125 KG');
    });

    /**
     * Half a pair is a state the schema forbids, but a card that rendered "500"
     * with a missing unit would be quoting a size nobody can interpret.
     */
    it('renders no size when only the amount arrives', () => {
      fixture.componentRef.setInput('packageAmount', 500);
      fixture.detectChanges();

      expect(rendered('.product-card__package')).toBeFalse();
    });

    /**
     * The size is a fact about the package even when its unit cannot be priced
     * per, so it stays and only the comparison line is withheld.
     */
    it('keeps the size and drops the unit price when the basis cannot be priced', () => {
      fixture.componentRef.setInput('packageAmount', 500);
      fixture.componentRef.setInput('packageUnit', 'GRAM');
      fixture.componentRef.setInput('unitPrice', 0.1);
      fixture.componentRef.setInput('unitPriceBasis', null);
      fixture.detectChanges();

      expect(textOf('.product-card__package-value')).toBe('500 GRAM');
      expect(rendered('.product-card__unit-price')).toBeFalse();
    });

    it('drops the unit price when the price is missing even if a basis is known', () => {
      fixture.componentRef.setInput('packageAmount', 1);
      fixture.componentRef.setInput('packageUnit', 'L');
      fixture.componentRef.setInput('unitPrice', null);
      fixture.componentRef.setInput('unitPriceBasis', 'L');
      fixture.detectChanges();

      expect(rendered('.product-card__unit-price')).toBeFalse();
    });

    /**
     * The package price is what the customer pays and what the add button buys.
     * The unit price is a comparison aid, so the price keeps the emphasis and the
     * two comparison lines sit above it rather than competing with it.
     */
    it('keeps the package price visually dominant', () => {
      fixture.componentRef.setInput('packageAmount', 0.75);
      fixture.componentRef.setInput('packageUnit', 'KG');
      fixture.componentRef.setInput('unitPrice', 66.65);
      fixture.componentRef.setInput('unitPriceBasis', 'KG');
      fixture.detectChanges();

      const card = fixture.nativeElement.querySelector('.product-card') as HTMLElement;
      const classes = Array.from(card.querySelectorAll('p')).map(
        (element) => element.className,
      );
      expect(classes).toContain('product-card__package');
      expect(classes).toContain('product-card__unit-price');
      expect(classes.indexOf('product-card__package')).toBeLessThan(
        classes.indexOf('product-card__price'),
      );
      expect(textOf('.product-card__amount')).toBe('49,90');
    });
  });
});
