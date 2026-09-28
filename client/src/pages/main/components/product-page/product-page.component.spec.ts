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

import { ProductPageComponent } from './product-page.component';

describe('ProductPageComponent', () => {
  let component: ProductPageComponent;
  let fixture: ComponentFixture<ProductPageComponent>;
  let httpMock: HttpTestingController;
  let navigateSpy: jasmine.Spy;

  let paramMap: BehaviorSubject<ParamMap>;
  let queryParamMap: BehaviorSubject<ParamMap>;
  let snapshot: { queryParams: Params; paramMap: ParamMap };

  const subCategories = [
    { subCategoryId: 3, subCategoryName: 'Süt', productCount: 12 },
    { subCategoryId: 3, subCategoryName: 'Peynir', productCount: 8 },
  ];

  function product(id: number, name = `Ürün ${id}`) {
    return { productId: id, productName: name, productPrice: 25, productCount: 3 };
  }

  function setRoute(
    params: Record<string, string>,
    query: Record<string, string>,
  ): void {
    snapshot.queryParams = { ...query };
    snapshot.paramMap = convertToParamMap(params);
    queryParamMap.next(convertToParamMap(query));
    paramMap.next(convertToParamMap(params));
  }

  function requests(fragment: string): TestRequest[] {
    return httpMock.match((request) => request.url.includes(fragment));
  }

  function expectRequest(fragment: string): TestRequest {
    return httpMock.expectOne((request) => request.url.includes(fragment));
  }

  function flushMetadata(
    categories: typeof subCategories = subCategories,
    totalCount = 40,
  ): void {
    expectRequest('getSubCategories').flush(categories);
    expectRequest('getProductCountsFromCategory').flush(totalCount);
    fixture.detectChanges();
  }

  function flushListing(items: ReturnType<typeof product>[]): void {
    expectRequest('getProductsFrom').flush(items);
    fixture.detectChanges();
  }

  function failMetadata(): void {
    expectRequest('getProductCountsFromCategory').flush('boom', {
      status: 500,
      statusText: 'Server Error',
    });
    httpMock
      .match((request) => request.url.includes('getSubCategories'))
      .forEach((request) => {
        if (!request.cancelled) {
          request.flush(null);
        }
      });
    fixture.detectChanges();
  }

  function flushProductImages(): void {
    requests('getProductImage').forEach((request) =>
      request.flush(new Blob(['x'], { type: 'image/png' })),
    );
    fixture.detectChanges();
  }

  beforeEach(async () => {
    paramMap = new BehaviorSubject<ParamMap>(convertToParamMap({ categoryId: '3' }));
    queryParamMap = new BehaviorSubject<ParamMap>(convertToParamMap({}));
    snapshot = {
      queryParams: {},
      paramMap: convertToParamMap({ categoryId: '3' }),
    };

    await TestBed.configureTestingModule({
      imports: [ProductPageComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap, queryParamMap, snapshot } },
      ],
    }).compileComponents();

    navigateSpy = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    httpMock = TestBed.inject(HttpTestingController);

    fixture = TestBed.createComponent(ProductPageComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    httpMock.match(() => true).forEach((request) => {
      if (request.cancelled) {
        return;
      }
      request.flush(
        request.request.responseType === 'blob'
          ? new Blob(['x'], { type: 'image/png' })
          : (null as never),
      );
    });
    httpMock.verify({ ignoreCancelled: true });
  });

  it('creates', () => {
    expect(component).toBeTruthy();
  });

  it('starts in a loading state and then renders the listing', () => {
    expect(component.isLoading).toBeTrue();
    expect(fixture.nativeElement.querySelector('.product-skeleton')).toBeTruthy();

    flushMetadata();
    expect(component.isLoading).toBeTrue();

    flushListing([product(1), product(2)]);

    expect(component.isLoading).toBeFalse();
    expect(fixture.nativeElement.querySelectorAll('app-product-preview').length).toBe(2);
  });

  it('shows the category name, the default selection and the category count', () => {
    flushMetadata(subCategories, 40);
    flushListing([product(1)]);

    expect(fixture.nativeElement.querySelector('.category-page__title').textContent).toContain(
      'Süt, Kahvaltılık',
    );
    expect(
      fixture.nativeElement.querySelector('.category-page__selection').textContent,
    ).toContain('Tüm ürünler');
    expect(
      fixture.nativeElement.querySelector('.category-page__count').textContent,
    ).toContain('40 ürün');
  });

  it('offers an All products option with a visible selected state', () => {
    flushMetadata();
    flushListing([product(1)]);

    const allProducts = fixture.nativeElement.querySelector(
      '.subcategory-nav__list .subcategory-nav__item',
    ) as HTMLButtonElement;

    expect(allProducts.textContent).toContain('Tüm ürünler');
    expect(allProducts.classList).toContain('is-selected');
    expect(allProducts.getAttribute('aria-current')).toBe('true');
  });

  it('marks the selected subcategory and reports the subcategory count', () => {
    setRoute({ categoryId: '3' }, { subcategory: 'Peynir' });
    flushMetadata();
    flushListing([product(1)]);

    const items = fixture.nativeElement.querySelectorAll('.subcategory-nav__item');
    expect(items[0].classList).not.toContain('is-selected');
    expect(items[2].classList).toContain('is-selected');
    expect(
      fixture.nativeElement.querySelector('.category-page__count').textContent,
    ).toContain('8 ürün');
    expect(
      fixture.nativeElement.querySelector('.category-page__selection').textContent,
    ).toContain('Peynir');
  });

  it('restores the subcategory and page from the URL on a shared link', () => {
    setRoute({ categoryId: '3' }, { subcategory: 'Süt', page: '2' });
    flushMetadata();
    fixture.detectChanges();

    const listing = expectRequest('getProductsFromSubcategory');
    expect(listing.request.params.get('subcategoryName')).toBe('Süt');
    expect(listing.request.params.get('page')).toBe('1');
    expect(listing.request.params.get('productRange')).toBe('10');

    listing.flush([product(1)]);
    fixture.detectChanges();
    expect(component.currentPage).toBe(2);
    expect(component.selectedSubCategoryName).toBe('Süt');
  });

  it('resets to page 1 when the subcategory changes', () => {
    setRoute({ categoryId: '3' }, { subcategory: 'Süt', page: '2' });
    flushMetadata();
    fixture.detectChanges();
    expectRequest('getProductsFromSubcategory').flush([product(1)]);
    fixture.detectChanges();
    expect(component.currentPage).toBe(2);

    component.selectSubCategory('Peynir');

    expect(navigateSpy).toHaveBeenCalledWith([], {
      relativeTo: jasmine.anything(),
      queryParams: { subcategory: 'Peynir' },
    });
  });

  it('omits both parameters for the default selection', () => {
    flushMetadata();
    flushListing([product(1)]);

    component.selectSubCategory('Süt');
    component.selectAllProducts();

    expect(navigateSpy.calls.argsFor(0)[1].queryParams).toEqual({ subcategory: 'Süt' });
    expect(navigateSpy.calls.argsFor(1)[1].queryParams).toEqual({});
  });

  it('omits the page parameter for the first page and keeps the subcategory', () => {
    flushMetadata();
    flushListing([product(1)]);

    component.onPageSelected(1);
    component.onPageSelected(3);

    expect(navigateSpy.calls.argsFor(0)[1].queryParams).toEqual({});
    expect(navigateSpy.calls.argsFor(1)[1].queryParams).toEqual({ page: '3' });

    setRoute({ categoryId: '3' }, { subcategory: 'Peynir' });
    fixture.detectChanges();
    component.onPageSelected(2);
    expect(navigateSpy.calls.argsFor(2)[1].queryParams).toEqual({
      subcategory: 'Peynir',
      page: '2',
    });
  });

  it('derives the page count from the existing count and page size', () => {
    flushMetadata(subCategories, 40);
    flushListing([product(1)]);

    expect(component.pageCount).toBe(4);
    expect(fixture.nativeElement.querySelector('app-product-page-switcher')).toBeTruthy();

    setRoute({ categoryId: '3' }, { subcategory: 'Peynir' });
    fixture.detectChanges();
    requests('getProductsFromSubcategory')[0].flush([product(1)]);
    fixture.detectChanges();

    expect(component.pageCount).toBe(1);
    expect(fixture.nativeElement.querySelector('app-product-page-switcher')).toBeNull();
  });

  it('hides the page switcher for a single page listing', () => {
    flushMetadata(subCategories, 5);
    flushListing([product(1)]);

    expect(component.pageCount).toBe(1);
    expect(fixture.nativeElement.querySelector('app-product-page-switcher')).toBeNull();
  });

  it('normalizes an unknown subcategory parameter to the default selection', () => {
    setRoute({ categoryId: '3' }, { subcategory: 'Olmayan' });
    flushMetadata();
    fixture.detectChanges();

    expect(component.selectedSubCategoryName).toBe('');
    expect(requests('getProductsFromSubcategory').length).toBe(0);
    expect(navigateSpy).toHaveBeenCalledWith([], {
      relativeTo: jasmine.anything(),
      queryParams: {},
      replaceUrl: true,
    });

    flushListing([product(1)]);
    expect(component.isEmptySelection).toBeFalse();
  });

  it('normalizes a non numeric page parameter to the first page', () => {
    setRoute({ categoryId: '3' }, { page: 'abc' });
    flushMetadata();

    expect(component.currentPage).toBe(1);
    expect(requests('getProductsFromCategory')[0].request.params.get('page')).toBe('0');
  });

  it('normalizes a zero or negative page parameter to the first page', () => {
    setRoute({ categoryId: '3' }, { page: '-4' });
    flushMetadata();

    expect(component.currentPage).toBe(1);
  });

  it('clamps a page beyond the last page to the last page', () => {
    setRoute({ categoryId: '3' }, { page: '99' });
    flushMetadata();

    expect(component.currentPage).toBe(4);
    expect(navigateSpy).toHaveBeenCalledWith([], {
      relativeTo: jasmine.anything(),
      queryParams: { page: '4' },
      replaceUrl: true,
    });
  });

  it('shows a retryable error state and reloads on retry', () => {
    failMetadata();

    expect(component.hasLoadError).toBeTrue();
    const alert = fixture.nativeElement.querySelector(
      '.listing-state--error',
    ) as HTMLElement;
    expect(alert.getAttribute('role')).toBe('alert');

    (fixture.nativeElement.querySelector('.listing-state__action') as HTMLButtonElement)
      .click();
    fixture.detectChanges();

    expect(component.hasLoadError).toBeFalse();
    flushMetadata();
    flushListing([product(1)]);
    expect(fixture.nativeElement.querySelectorAll('app-product-preview').length).toBe(1);
  });

  it('retries only the listing when the metadata already loaded', () => {
    flushMetadata();
    expectRequest('getProductsFromCategory').flush('boom', {
      status: 500,
      statusText: 'Server Error',
    });
    fixture.detectChanges();
    expect(component.hasLoadError).toBeTrue();

    component.retry();
    fixture.detectChanges();

    expect(requests('getSubCategories').length).toBe(0);
    flushListing([product(7)]);
    expect(component.items.length).toBe(1);
  });

  it('shows an empty state for a category without products', () => {
    flushMetadata(subCategories, 0);

    expect(fixture.nativeElement.querySelectorAll('.product-skeleton').length).toBe(0);
    expect(fixture.nativeElement.querySelector('.listing-state')).toBeTruthy();
    expect(requests('getProductsFromCategory').length).toBe(0);
  });

  it('treats the plain 404 of an empty category as an empty listing', () => {
    flushMetadata(subCategories, 40);
    expectRequest('getProductsFromCategory').flush('3', {
      status: 404,
      statusText: 'Not Found',
    });
    fixture.detectChanges();

    expect(component.hasLoadError).toBeFalse();
    expect(component.isEmptySelection).toBeTrue();
    expect(
      fixture.nativeElement.querySelector('.listing-state__title').textContent,
    ).toContain('Bu seçimde ürün bulunamadı');
  });

  it('still reports an unrelated 404 as a retryable error', () => {
    flushMetadata(subCategories, 40);
    expectRequest('getProductsFromCategory').flush('<html>Not Found</html>', {
      status: 404,
      statusText: 'Not Found',
    });
    fixture.detectChanges();

    expect(component.hasLoadError).toBeTrue();
    expect(fixture.nativeElement.querySelector('.listing-state--error')).toBeTruthy();
  });

  it('shows an empty state when the listing returns no rows', () => {
    flushMetadata();
    flushListing([]);

    expect(component.hasLoadError).toBeFalse();
    expect(fixture.nativeElement.querySelector('.listing-state__title').textContent).toContain(
      'Bu seçimde ürün bulunamadı',
    );
  });

  it('ignores a slow response that a newer request has replaced', () => {
    flushMetadata();
    const stale = expectRequest('getProductsFromCategory');

    setRoute({ categoryId: '3' }, { page: '2' });
    fixture.detectChanges();
    const fresh = expectRequest('getProductsFromCategory');
    expect(fresh).not.toBe(stale);

    stale.flush([product(1)]);
    fixture.detectChanges();
    expect(component.items.length).toBe(0);

    fresh.flush([product(2), product(3)]);
    fixture.detectChanges();
    expect(component.items.length).toBe(2);
  });

  it('renders a breadcrumb and a return link that preserve the originating state', () => {
    setRoute({ categoryId: '3' }, { subcategory: 'Süt', page: '2' });
    flushMetadata();
    expectRequest('getProductsFromSubcategory').flush([product(1)]);
    fixture.detectChanges();

    setRoute({ categoryId: '3', productId: '11' }, { subcategory: 'Süt', page: '2' });
    fixture.detectChanges();

    expect(component.isProductDetailView).toBeTrue();
    expect(fixture.nativeElement.querySelector('.breadcrumb-nav')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('.subcategory-nav')).toBeNull();

    const backLink = fixture.nativeElement.querySelector('.back-link') as HTMLAnchorElement;
    expect(backLink.textContent).toContain('Süt listesine dön');
    expect(backLink.getAttribute('href')).toContain('subcategory=S%C3%BCt');
    expect(backLink.getAttribute('href')).toContain('page=2');
    expect(backLink.getAttribute('href')).toContain('/category/3');

    const breadcrumbText = fixture.nativeElement.querySelector(
      '.breadcrumb-nav__list',
    ).textContent as string;
    expect(breadcrumbText).toContain('Süt, Kahvaltılık');
    expect(breadcrumbText).toContain('Süt');
  });

  it('resets the selection when the category changes', () => {
    setRoute({ categoryId: '3' }, { subcategory: 'Süt', page: '2' });
    flushMetadata();
    requests('getProductsFromSubcategory')[0].flush([product(1)]);
    fixture.detectChanges();
    expect(component.selectedSubCategoryName).toBe('Süt');

    setRoute({ categoryId: '6' }, { subcategory: 'Süt', page: '2' });
    fixture.detectChanges();

    expect(component.categoryName).toBe('İçecek');
    expect(component.selectedSubCategoryName).toBe('');
    expect(component.currentPage).toBe(1);
  });
});
