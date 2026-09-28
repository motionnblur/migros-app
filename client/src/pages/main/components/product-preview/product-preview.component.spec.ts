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

  it('shows the effective price returned by the API without an original price', () => {
    const price = fixture.nativeElement.querySelector('.product-card__price')
      .textContent as string;

    expect(price).toContain('49.90');
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
  });});
