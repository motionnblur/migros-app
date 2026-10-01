import { HttpParams } from '@angular/common/http';
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
    { subCategoryName: 'Süt', productCount: 12 },
    { subCategoryName: 'Peynir', productCount: 8 },
  ];

  function product(id: number, name = `Ürün ${id}`, count = 3) {
    return { productId: id, productName: name, productPrice: 25, productCount: count };
  }

  function searchPage(
    items: ReturnType<typeof product>[],
    totalItems: number,
    subcategories: typeof subCategories = subCategories,
  ) {
    return { items, totalItems, page: 0, size: 10, subcategories };
  }

  function setRoute(
    params: Record<string, string>,
    query: Record<string, string>,
  ): void {
    snapshot.queryParams = { ...query };
    snapshot.paramMap = convertToParamMap(params);
    queryParamMap.next(convertToParamMap(query));
    paramMap.next(convertToParamMap(params));
    fixture.detectChanges();
  }

  function requests(fragment: string): TestRequest[] {
    return httpMock.match((request) => request.url.includes(fragment));
  }

  function expectSearch(): TestRequest {
    return httpMock.expectOne((request) => request.url.includes('searchProducts'));
  }

  function flushListing(
    items: ReturnType<typeof product>[],
    totalItems = 40,
    subcategories: typeof subCategories = subCategories,
  ): void {
    expectSearch().flush(searchPage(items, totalItems, subcategories));
    fixture.detectChanges();
  }

  function failSearch(): void {
    expectSearch().flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
  }

  function flushProductImages(): void {
    requests('getProductImage').forEach((request) =>
      request.flush(new Blob(['x'], { type: 'image/png' })),
    );
    fixture.detectChanges();
  }

  function flushProductDetail(productId: number, productName: string): void {
    requests('getProductDataWithProductId').forEach((request) =>
      request.flush({
        productId,
        productName,
        subCategoryName: 'Süt',
        productPrice: 50,
        productCount: 4,
        productDiscount: 0,
        productCategoryId: 3,
      }),
    );
    fixture.detectChanges();
    requests('getProductDescription').forEach((request) =>
      request.flush({ productId, descriptionList: [] }),
    );
    flushProductImages();
  }

  /**
   * The category listing used to ask three separate endpoints for the rows, the
   * total and the buckets. It now asks once, so the numbers in the sidebar, the
   * header and the paginator cannot describe three different sets.
   */
  let firstRequest: { url: string; params: HttpParams };

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

    // The listing loads as soon as the route does, so every test starts from a
    // settled page and its own navigation is the thing under test.
    const initial = expectSearch();
    firstRequest = { url: initial.request.url, params: initial.request.params };
    initial.flush(searchPage([], 0, []));
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

  it('loads the whole page through the one catalogue search endpoint', () => {
    expect(firstRequest.url).toBe('/user/supply/searchProducts');
    expect(firstRequest.params.get('categoryId')).toBe('3');
    expect(firstRequest.params.get('page')).toBe('0');
    expect(firstRequest.params.get('size')).toBe('10');
    expect(firstRequest.params.has('subcategory')).toBeFalse();
  });

  it('renders the listing and drops the loading skeleton', () => {
    setRoute({ categoryId: '3' }, { sort: 'PRICE_ASC' });
    expect(component.isLoading).toBeTrue();
    expect(fixture.nativeElement.querySelector('.listing__skeleton')).toBeTruthy();

    flushListing([product(1), product(2)]);

    expect(component.isLoading).toBeFalse();
    expect(fixture.nativeElement.querySelectorAll('app-product-preview').length).toBe(2);
    expect(fixture.nativeElement.querySelector('.listing__skeleton')).toBeNull();
  });

  it('shows the category name, the default selection and the filtered total', () => {
    setRoute({ categoryId: '3' }, { sort: 'PRICE_ASC' });
    flushListing([product(1)], 40);

    expect(
      fixture.nativeElement.querySelector('.listing__title').textContent,
    ).toContain('Süt, Kahvaltılık');
    expect(
      fixture.nativeElement.querySelector('.listing__selection').textContent,
    ).toContain('Tüm ürünler');
    expect(
      fixture.nativeElement.querySelector('.listing__count').textContent,
    ).toContain('40 ürün');
    expect(
      fixture.nativeElement.querySelector('.subcategory-nav__count').textContent,
    ).toContain('40');
  });

  it('offers an All products option with a visible selected state', () => {
    setRoute({ categoryId: '3' }, { sort: 'PRICE_ASC' });
    flushListing([product(1)], 40);

    const allProducts = fixture.nativeElement.querySelector(
      '.subcategory-nav__list .subcategory-nav__item',
    ) as HTMLButtonElement;

    expect(allProducts.textContent).toContain('Tüm ürünler');
    expect(allProducts.classList).toContain('is-selected');
    expect(allProducts.getAttribute('aria-current')).toBe('true');
  });

  it('marks the selected subcategory and scopes the request to it', () => {
    setRoute({ categoryId: '3' }, { subcategory: 'Peynir' });

    const request = expectSearch();
    expect(request.request.params.get('subcategory')).toBe('Peynir');

    request.flush(searchPage([product(1)], 8, subCategories));
    fixture.detectChanges();

    const items = fixture.nativeElement.querySelectorAll('.subcategory-nav__item');
    expect(items[0].classList).not.toContain('is-selected');
    expect(items[2].classList).toContain('is-selected');
    expect(
      fixture.nativeElement.querySelector('.listing__count').textContent,
    ).toContain('8 ürün');
    expect(
      fixture.nativeElement.querySelector('.listing__selection').textContent,
    ).toContain('Peynir');
  });

  /**
   * With a subcategory selected, `totalItems` describes that bucket, so the
   * category's own figure has to come from the buckets instead - the same numbers
   * shown next to each bucket, so the sidebar stays internally comparable.
   */
  it('reports the category total from the buckets while a subcategory is selected', () => {
    setRoute({ categoryId: '3' }, { subcategory: 'Peynir' });
    expectSearch().flush(searchPage([product(1)], 8, subCategories));
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('.subcategory-nav__count').textContent,
    ).toContain('20');
  });

  it('restores the subcategory and page from the URL on a shared link', () => {
    setRoute({ categoryId: '3' }, { subcategory: 'Süt', page: '2' });

    const listing = expectSearch();
    expect(listing.request.params.get('subcategory')).toBe('Süt');
    expect(listing.request.params.get('page')).toBe('1');
    expect(listing.request.params.get('size')).toBe('10');

    listing.flush(searchPage([product(1)], 40, subCategories));
    fixture.detectChanges();
    expect(component.currentPage).toBe(2);
    expect(component.selectedSubCategoryName).toBe('Süt');
  });

  it('resets to page 1 when the subcategory changes', () => {
    setRoute({ categoryId: '3' }, { subcategory: 'Süt', page: '2' });
    flushListing([product(1)], 40);
    expect(component.currentPage).toBe(2);

    component.selectSubCategory('Peynir');

    expect(navigateSpy).toHaveBeenCalledWith([], {
      relativeTo: jasmine.anything(),
      queryParams: { subcategory: 'Peynir' },
    });
  });

  it('omits both parameters for the default selection', () => {
    setRoute({ categoryId: '3' }, { sort: 'PRICE_ASC' });
    flushListing([product(1)], 40);

    component.selectSubCategory('Süt');
    component.selectAllProducts();

    expect(navigateSpy.calls.argsFor(0)[1].queryParams).toEqual({
      subcategory: 'Süt',
      sort: 'PRICE_ASC',
    });
    expect(navigateSpy.calls.argsFor(1)[1].queryParams).toEqual({ sort: 'PRICE_ASC' });
  });

  it('omits the page parameter for the first page and keeps the filters', () => {
    setRoute({ categoryId: '3' }, { sort: 'PRICE_ASC' });
    flushListing([product(1)], 40, []);

    component.onPageSelected(1);
    component.onPageSelected(3);

    expect(navigateSpy.calls.argsFor(0)[1].queryParams).toEqual({ sort: 'PRICE_ASC' });
    expect(navigateSpy.calls.argsFor(1)[1].queryParams).toEqual({
      sort: 'PRICE_ASC',
      page: '3',
    });
  });

  it('derives the page count from the server total and the page size', () => {
    setRoute({ categoryId: '3' }, { sort: 'PRICE_ASC' });
    flushListing([product(1)], 40);

    expect(component.pageCount).toBe(4);
    expect(fixture.nativeElement.querySelector('app-product-page-switcher')).toBeTruthy();

    setRoute({ categoryId: '3' }, { subcategory: 'Peynir', sort: 'PRICE_ASC' });
    expectSearch().flush(searchPage([product(1)], 8, subCategories));
    fixture.detectChanges();

    expect(component.pageCount).toBe(1);
    expect(fixture.nativeElement.querySelector('app-product-page-switcher')).toBeNull();
  });

  it('hides the page switcher for a single page listing', () => {
    setRoute({ categoryId: '3' }, { sort: 'PRICE_ASC' });
    flushListing([product(1)], 5);

    expect(component.pageCount).toBe(1);
    expect(fixture.nativeElement.querySelector('app-product-page-switcher')).toBeNull();
  });

  /**
   * A subcategory name cannot be validated before the request - only the server
   * knows which buckets exist - so a stale shared link is repaired from the
   * response instead of staying permanently empty.
   */
  it('repairs a subcategory the server does not offer and refetches the category', () => {
    setRoute({ categoryId: '3' }, { subcategory: 'Olmayan' });
    expectSearch().flush(searchPage([], 0, subCategories));
    fixture.detectChanges();

    expect(component.selectedSubCategoryName).toBe('');

    const repaired = expectSearch();
    expect(repaired.request.params.has('subcategory')).toBeFalse();

    repaired.flush(searchPage([product(1)], 40, subCategories));
    fixture.detectChanges();

    expect(component.items.length).toBe(1);
    expect(navigateSpy).toHaveBeenCalledWith([], {
      relativeTo: jasmine.anything(),
      queryParams: {},
      replaceUrl: true,
    });
  });

  it('normalizes an invalid page parameter to the first page', () => {
    setRoute({ categoryId: '3' }, { page: 'abc', sort: 'PRICE_ASC' });

    expect(component.currentPage).toBe(1);
    expect(requests('searchProducts')[0].request.params.get('page')).toBe('0');
    expect(navigateSpy).toHaveBeenCalledWith([], {
      relativeTo: jasmine.anything(),
      queryParams: { sort: 'PRICE_ASC' },
      replaceUrl: true,
    });
  });

  it('normalizes a zero or negative page parameter to the first page', () => {
    setRoute({ categoryId: '3' }, { page: '-4' });

    expect(component.currentPage).toBe(1);
  });

  it('clamps a page beyond the last page to the last page', () => {
    setRoute({ categoryId: '3' }, { page: '99' });

    const asked = expectSearch();
    expect(asked.request.params.get('page')).toBe('98');
    asked.flush(searchPage([], 40, subCategories));
    fixture.detectChanges();

    const repaired = expectSearch();
    expect(repaired.request.params.get('page')).toBe('3');
    repaired.flush(searchPage([product(40)], 40, subCategories));
    fixture.detectChanges();

    expect(component.currentPage).toBe(4);
    expect(navigateSpy).toHaveBeenCalledWith([], {
      relativeTo: jasmine.anything(),
      queryParams: { page: '4' },
      replaceUrl: true,
    });
  });

  describe('filters', () => {
    it('sends every control the shared toolbar offers', () => {
      setRoute(
        { categoryId: '3' },
        {
          availability: 'IN_STOCK',
          minPrice: '10',
          maxPrice: '50',
          discounted: 'true',
          sort: 'PRICE_ASC',
        },
      );
      const request = expectSearch();

      expect(request.request.params.get('categoryId')).toBe('3');
      expect(request.request.params.get('availability')).toBe('IN_STOCK');
      expect(request.request.params.get('minPrice')).toBe('10');
      expect(request.request.params.get('maxPrice')).toBe('50');
      expect(request.request.params.get('discountedOnly')).toBe('true');
      expect(request.request.params.get('sort')).toBe('PRICE_ASC');

      request.flush(searchPage([product(1)], 1, []));
      fixture.detectChanges();
    });

    it('keeps the subcategory, the page and the filters on one card link', () => {
      setRoute(
        { categoryId: '3' },
        { subcategory: 'Süt', page: '2', availability: 'IN_STOCK' },
      );
      expectSearch().flush(searchPage([product(1)], 40, subCategories));
      fixture.detectChanges();
      flushProductImages();

      const href =
        (fixture.nativeElement.querySelector('a.product-card__link') as HTMLAnchorElement)
          .getAttribute('href') ?? '';
      expect(href).toContain('/category/3/product/1');
      expect(href).toContain('subcategory=S%C3%BCt');
      expect(href).toContain('page=2');
      expect(href).toContain('availability=IN_STOCK');
    });

    it('shows sold-out products by default and marks them', () => {
      setRoute({ categoryId: '3' }, { sort: 'PRICE_ASC' });
      flushListing([product(1, 'Tüketilmemiş', 0), product(2)], 2);
      flushProductImages();

      const cards = fixture.nativeElement.querySelectorAll('app-product-preview');
      expect(cards.length).toBe(2);
      expect(
        cards[0].querySelector('.product-card__stock').textContent,
      ).toContain('Tükendi');
      expect(
        (cards[0].querySelector('.product-card__add') as HTMLButtonElement).disabled,
      ).toBeTrue();
    });
  });

  it('shows a retryable error state and reloads on retry', () => {
    setRoute({ categoryId: '3' }, { sort: 'PRICE_ASC' });
    failSearch();

    expect(component.hasLoadError).toBeTrue();
    const alert = fixture.nativeElement.querySelector(
      '.listing-state--error',
    ) as HTMLElement;
    expect(alert.getAttribute('role')).toBe('alert');

    (fixture.nativeElement.querySelector('.listing-state__action') as HTMLButtonElement)
      .click();
    fixture.detectChanges();

    expect(component.hasLoadError).toBeFalse();
    flushListing([product(1)]);
    expect(fixture.nativeElement.querySelectorAll('app-product-preview').length).toBe(1);
  });

  it('shows an empty state for a category without products', () => {
    setRoute({ categoryId: '3' }, { sort: 'PRICE_ASC' });
    flushListing([], 0, []);

    expect(fixture.nativeElement.querySelectorAll('.listing__skeleton').length).toBe(0);
    expect(fixture.nativeElement.querySelector('.listing-state')).toBeTruthy();
    expect(
      fixture.nativeElement.querySelector('.listing-state__title').textContent,
    ).toContain('Bu seçimde ürün bulunamadı');
  });

  it('shows an empty state when the listing returns no rows', () => {
    setRoute({ categoryId: '3' }, { sort: 'PRICE_ASC' });
    flushListing([], 40, subCategories);

    expect(component.hasLoadError).toBeFalse();
    expect(
      fixture.nativeElement.querySelector('.listing-state__title').textContent,
    ).toContain('Bu seçimde ürün bulunamadı');
  });

  it('does not run a second query for the same state', () => {
    setRoute({ categoryId: '3' }, {});

    expect(requests('searchProducts').length).toBe(0);
  });

  it('reloads the whole category when the category in the URL changes', () => {
    setRoute({ categoryId: '6' }, { sort: 'PRICE_ASC' });
    const request = requests('searchProducts').find(
      (candidate) => candidate.request.params.get('categoryId') === '6',
    );
    expect(request).toBeTruthy();
    expect(request!.request.params.get('sort')).toBe('PRICE_ASC');

    requests('searchProducts')
      .filter((candidate) => candidate !== request)
      .forEach((stale) => {
        if (!stale.cancelled) {
          stale.flush(searchPage([], 0, []));
        }
      });
    request!.flush(
      searchPage([product(60)], 2, [{ subCategoryName: 'Meyve Suyu', productCount: 2 }]),
    );
    fixture.detectChanges();

    expect(component.categoryName).toBe('İçecek');
    expect(component.subCategories.map((item) => item.subCategoryName)).toEqual([
      'Meyve Suyu',
    ]);
    expect(component.totalProductCount).toBe(2);
    expect(component.items.map((item) => item.productId)).toEqual([60]);
  });

  it('renders a breadcrumb and a return link that preserve the originating state', () => {
    setRoute({ categoryId: '3' }, { subcategory: 'Süt', page: '2' });
    expectSearch().flush(searchPage([product(1)], 40, subCategories));
    fixture.detectChanges();

    setRoute({ categoryId: '3', productId: '11' }, { subcategory: 'Süt', page: '2' });

    expect(component.isProductDetailView).toBeTrue();
    expect(component.isStandaloneDetailView).toBeFalse();
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

  it('keeps a deep product link browsing state and reloads it on the way back', () => {
    setRoute({ categoryId: '3', productId: '11' }, { subcategory: 'Süt', page: '2' });
    // The listing the category route had open is superseded by the detail view.
    requests('searchProducts').forEach((stale) => {
      if (!stale.cancelled) {
        stale.flush(searchPage([], 0, []));
      }
    });
    fixture.detectChanges();

    expect(component.isProductDetailView).toBeTrue();
    expect(requests('searchProducts').length).toBe(0);

    setRoute({ categoryId: '3' }, { subcategory: 'Süt', page: '2' });

    const listing = expectSearch();
    expect(listing.request.params.get('subcategory')).toBe('Süt');
    expect(listing.request.params.get('page')).toBe('1');
    listing.flush(searchPage([product(1)], 40, subCategories));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('app-product-preview').length).toBe(1);
    expect(component.currentPage).toBe(2);
  });

  describe('a product reached without a category', () => {
    beforeEach(() => {
      setRoute({ productId: '11' }, { q: 'çiçek', page: '3' });
      // The category listing the previous route had open is superseded here.
      requests('searchProducts').forEach((stale) => {
        if (!stale.cancelled) {
          stale.flush(searchPage([], 0, []));
        }
      });
      fixture.detectChanges();
    });

    it('opens the product without asking for a category listing', () => {
      expect(component.isProductDetailView).toBeTrue();
      expect(component.isStandaloneDetailView).toBeTrue();
      expect(requests('searchProducts').length).toBe(0);
      expect(component.isLoading).toBeFalse();
      expect(fixture.nativeElement.querySelector('app-product-buy')).toBeTruthy();
    });

    it('sends the customer back to the search results with their filters', () => {
      const backLink = fixture.nativeElement.querySelector('.back-link') as HTMLAnchorElement;

      expect(backLink.getAttribute('href')).toBe('/search?q=%C3%A7i%C3%A7ek&page=3');
      expect(backLink.textContent).toContain('Arama sonuçlarına dön');
      expect(
        fixture.nativeElement.querySelector('.breadcrumb-nav__list').textContent,
      ).toContain('Arama sonuçları');
    });

    it('never renders a category breadcrumb or a category listing for it', () => {
      expect(fixture.nativeElement.querySelector('.subcategory-nav')).toBeNull();
      expect(
        fixture.nativeElement.querySelector('.breadcrumb-nav__list').textContent,
      ).not.toContain('Ana sayfa');
    });
  });

  it('clears the breadcrumb product name when the routed product changes', () => {
    setRoute({ categoryId: '3', productId: '11' }, {});
    fixture.detectChanges();

    flushProductDetail(11, 'Tam Süt');
    expect(component.productName).toBe('Tam Süt');
    expect(
      fixture.nativeElement.querySelector('.breadcrumb-nav__item--current').textContent,
    ).toContain('Tam Süt');

    setRoute({ categoryId: '3', productId: '12' }, {});
    fixture.detectChanges();

    expect(component.productName).toBe('');
    expect(fixture.nativeElement.querySelector('.breadcrumb-nav__item--current')).toBeNull();

    flushProductDetail(12, 'Yoğurt');
    expect(component.productName).toBe('Yoğurt');
    expect(
      fixture.nativeElement.querySelector('.breadcrumb-nav__item--current').textContent,
    ).toContain('Yoğurt');
  });
});