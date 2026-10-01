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

import { SearchResultsComponent } from './search-results.component';

describe('SearchResultsComponent', () => {
  let component: SearchResultsComponent;
  let fixture: ComponentFixture<SearchResultsComponent>;
  let httpMock: HttpTestingController;
  let navigateSpy: jasmine.Spy;
  let element: HTMLElement;

  let queryParamMap: BehaviorSubject<ParamMap>;
  let snapshot: { queryParams: Params };

  /**
   * The route loads as soon as it is created, so every test starts from a settled
   * listing at `/search?q=süt` and its own navigation is the thing under test.
   * What that first request looked like is kept here, because reproducing it is
   * exactly what a shared link depends on.
   */
  let firstRequest: { url: string; method: string; params: HttpParams };

  function product(id: number, count = 3, price = 25) {
    return {
      productId: id,
      productName: `Ürün ${id}`,
      productPrice: price,
      productCount: count,
    };
  }

  function emptyPage() {
    return { items: [], totalItems: 0, page: 0, size: 10, subcategories: [] };
  }

  function page(items: ReturnType<typeof product>[], totalItems: number, pageIndex = 0) {
    return { items, totalItems, page: pageIndex, size: 10, subcategories: [] };
  }

  function setQuery(params: Record<string, string>): void {
    snapshot.queryParams = { ...params };
    queryParamMap.next(convertToParamMap(params));
    fixture.detectChanges();
  }

  function expectSearch(): TestRequest {
    return httpMock.expectOne((request) => request.url.includes('searchProducts'));
  }

  function flush(items: ReturnType<typeof product>[], totalItems = items.length): void {
    expectSearch().flush(page(items, totalItems));
    fixture.detectChanges();
  }

  /** Navigates and settles, so a test reads as the journey it describes. */
  function loadListing(
    params: Record<string, string>,
    items: ReturnType<typeof product>[],
    totalItems = items.length,
  ): void {
    setQuery(params);
    flush(items, totalItems);
  }

  function flushImages(): void {
    httpMock
      .match((request) => request.url.includes('getProductImage'))
      .forEach((request) => {
        if (!request.cancelled) {
          request.flush(new Blob(['x'], { type: 'image/png' }));
        }
      });
    fixture.detectChanges();
  }

  function query<T extends Element = HTMLElement>(selector: string): T {
    return element.querySelector(selector) as T;
  }

  function queryAll<T extends Element = HTMLElement>(selector: string): T[] {
    return Array.from(element.querySelectorAll(selector) as NodeListOf<T>);
  }

  beforeEach(async () => {
    queryParamMap = new BehaviorSubject<ParamMap>(convertToParamMap({ q: 'süt' }));
    snapshot = { queryParams: { q: 'süt' } };

    await TestBed.configureTestingModule({
      imports: [SearchResultsComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            queryParamMap,
            snapshot,
            paramMap: convertToParamMap({}),
          },
        },
      ],
    }).compileComponents();

    navigateSpy = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    httpMock = TestBed.inject(HttpTestingController);

    fixture = TestBed.createComponent(SearchResultsComponent);
    component = fixture.componentInstance;
    element = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();

    const initial = expectSearch();
    firstRequest = {
      url: initial.request.url,
      method: initial.request.method,
      params: initial.request.params,
    };
    initial.flush(emptyPage());
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

  it('reads the catalogue search endpoint with a GET', () => {
    expect(firstRequest.url).toBe('/user/supply/searchProducts');
    expect(firstRequest.method).toBe('GET');
    expect(firstRequest.params.get('q')).toBe('süt');
    expect(firstRequest.params.get('page')).toBe('0');
    expect(firstRequest.params.get('size')).toBe('10');
  });

  it('leaves the endpoint defaults implicit on the first request', () => {
    expect(firstRequest.params.has('availability')).toBeFalse();
    expect(firstRequest.params.has('sort')).toBeFalse();
    expect(firstRequest.params.has('discountedOnly')).toBeFalse();
    expect(firstRequest.params.has('minPrice')).toBeFalse();
    expect(firstRequest.params.has('maxPrice')).toBeFalse();
  });

  /**
   * A search result may belong to any category. Sending one would scope the search
   * to a category the customer never chose, and the endpoint refuses a
   * subcategory without a category outright.
   */
  it('never scopes the search to a category or a subcategory', () => {
    expect(firstRequest.params.has('categoryId')).toBeFalse();
    expect(firstRequest.params.has('subcategory')).toBeFalse();
  });

  it('renders the term, the server total and one card per row', () => {
    loadListing({ q: 'süt', page: '2' }, [product(1), product(2)], 42);

    expect(query('.listing__title').textContent).toContain('“süt” için sonuçlar');
    expect(query('.listing__count').textContent).toContain('42 ürün');
    expect(queryAll('app-product-preview').length).toBe(2);
  });

  it('sizes the paginator from the server total', () => {
    loadListing({ q: 'süt', page: '2' }, [product(1)], 42);

    expect(component.pageCount).toBe(5);
    expect(query('app-product-page-switcher')).toBeTruthy();
    expect(query('.page-switcher__status').textContent).toContain('Sayfa 2 / 5');
  });

  it('browses the whole catalogue when no term was given', () => {
    setQuery({});

    const request = expectSearch();
    expect(request.request.params.has('q')).toBeFalse();
    expect(query('.listing__title').textContent).toContain('Tüm ürünler');

    request.flush(page([product(1), product(2)], 2));
    fixture.detectChanges();
    expect(queryAll('app-product-preview').length).toBe(2);
  });

  it('clamps a page beyond the last one and refetches the last page', () => {
    loadListing({ q: 'süt', page: '2' }, [product(1)], 42);

    setQuery({ q: 'süt', page: '99' });
    const asked = expectSearch();
    expect(asked.request.params.get('page')).toBe('98');

    asked.flush(page([], 42, 98));
    fixture.detectChanges();

    const repaired = expectSearch();
    expect(repaired.request.params.get('page')).toBe('4');
    repaired.flush(page([product(40)], 42, 4));
    fixture.detectChanges();

    expect(component.currentPage).toBe(5);
    expect(navigateSpy).toHaveBeenCalledWith([], {
      relativeTo: jasmine.anything(),
      queryParams: { q: 'süt', page: '5' },
      replaceUrl: true,
    });
  });

  it('normalizes an invalid page parameter to the first page', () => {
    loadListing({ q: 'süt', page: '2' }, [product(1)], 25);
    expect(component.currentPage).toBe(2);

    setQuery({ q: 'süt', page: 'abc' });

    // Normalized before the request, so the endpoint is never asked for a page
    // it would refuse.
    const requested = expectSearch();
    expect(requested.request.params.get('page')).toBe('0');
    requested.flush(page([product(1), product(2)], 25));
    fixture.detectChanges();

    expect(component.currentPage).toBe(1);
    expect(queryAll('app-product-preview').length).toBe(2);
    expect(navigateSpy).toHaveBeenCalledWith([], {
      relativeTo: jasmine.anything(),
      queryParams: { q: 'süt' },
      replaceUrl: true,
    });
  });

  describe('filters from the URL', () => {
    it('sends every filter a shared link carries', () => {
      setQuery({
        q: 'süt',
        availability: 'IN_STOCK',
        minPrice: '10',
        maxPrice: '50',
        discounted: 'true',
        sort: 'PRICE_ASC',
      });

      const request = expectSearch();
      expect(request.request.params.get('availability')).toBe('IN_STOCK');
      expect(request.request.params.get('minPrice')).toBe('10');
      expect(request.request.params.get('maxPrice')).toBe('50');
      expect(request.request.params.get('discountedOnly')).toBe('true');
      expect(request.request.params.get('sort')).toBe('PRICE_ASC');

      request.flush(page([product(1)], 1));
      fixture.detectChanges();

      expect(queryAll('.listing__chip-text').length).toBe(4);
    });

    it('normalizes a lowercase enum in a shared link rather than forwarding it', () => {
      setQuery({ q: 'süt', availability: 'in_stock' });

      const request = expectSearch();
      expect(request.request.params.get('availability')).toBe('IN_STOCK');

      request.flush(emptyPage());
      fixture.detectChanges();

      expect(navigateSpy).toHaveBeenCalledWith([], {
        relativeTo: jasmine.anything(),
        queryParams: { q: 'süt', availability: 'IN_STOCK' },
        replaceUrl: true,
      });
    });

    it('drops an inverted price pair instead of requesting a 400', () => {
      setQuery({ q: 'süt', minPrice: '90', maxPrice: '10' });

      const request = expectSearch();
      expect(request.request.params.get('minPrice')).toBe('90');
      expect(request.request.params.has('maxPrice')).toBeFalse();

      request.flush(emptyPage());
      fixture.detectChanges();
    });

    it('drops an unrecognized sort instead of forwarding it', () => {
      loadListing({ q: 'süt', sort: 'PRICE_ASC' }, [product(1)], 1);
      expect(component.filters.sort).toBe('PRICE_ASC');

      setQuery({ q: 'süt', sort: 'cheapest' });

      const request = expectSearch();
      expect(request.request.params.has('sort')).toBeFalse();

      request.flush(page([product(1)], 1));
      fixture.detectChanges();
      expect(component.filters.sort).toBe('DEFAULT');
    });
  });

  describe('controls', () => {
    beforeEach(() => {
      loadListing({ q: 'süt', page: '2' }, [product(1), product(2)], 42);
    });

    it('puts a filter change in the URL and resets to the first page', () => {
      const segments = queryAll<HTMLButtonElement>('.listing__segment');
      segments[1].click();

      expect(navigateSpy).toHaveBeenCalledWith([], {
        relativeTo: jasmine.anything(),
        queryParams: { q: 'süt', availability: 'IN_STOCK' },
      });
    });

    it('keeps the term while the page changes', () => {
      component.onRequestChange({ filters: component.filters, page: 3 });

      expect(navigateSpy).toHaveBeenCalledWith([], {
        relativeTo: jasmine.anything(),
        queryParams: { q: 'süt', page: '3' },
      });
    });

    it('reloads the listing after the URL comes back with the new filter', () => {
      setQuery({ q: 'süt', availability: 'OUT_OF_STOCK', page: '2' });

      const request = expectSearch();
      expect(request.request.params.get('availability')).toBe('OUT_OF_STOCK');
      expect(request.request.params.get('page')).toBe('1');

      request.flush(page([product(9, 0)], 30, 1));
      fixture.detectChanges();

      expect(component.currentPage).toBe(2);
      expect(query('.listing__count').textContent).toContain('30 ürün');
    });

    it('ignores a slow response a newer navigation has replaced', () => {
      setQuery({ q: 'süt', page: '3' });
      const stale = expectSearch();

      setQuery({ q: 'yoğurt' });
      const fresh = expectSearch();
      expect(fresh).not.toBe(stale);
      // The superseded request is unsubscribed, so its response can neither render
      // nor overwrite the new listing.
      expect(stale.cancelled).toBeTrue();

      fresh.flush(page([product(2), product(3)], 2));
      fixture.detectChanges();
      expect(component.items.length).toBe(2);
      expect(query('.listing__title').textContent).toContain('yoğurt');
    });

    it('walks the filter history through the browser Back button', () => {
      setQuery({ q: 'süt', availability: 'IN_STOCK' });
      expectSearch().flush(emptyPage());
      fixture.detectChanges();
      expect(component.filters.availability).toBe('IN_STOCK');

      // Back: the filter is gone from the URL, so the listing goes with it.
      setQuery({ q: 'süt' });
      const restored = expectSearch();
      expect(restored.request.params.has('availability')).toBeFalse();

      restored.flush(page([product(1), product(2)], 2));
      fixture.detectChanges();

      expect(component.filters.availability).toBe('ALL');
      expect(queryAll('app-product-preview').length).toBe(2);
    });

    it('restores the page from the URL on a reload', () => {
      setQuery({ q: 'süt', page: '3' });

      const request = expectSearch();
      expect(request.request.params.get('page')).toBe('2');

      request.flush(page([product(25)], 42, 2));
      fixture.detectChanges();

      expect(component.currentPage).toBe(3);
      expect(query('.page-switcher__status').textContent).toContain('Sayfa 3 / 5');
    });
  });

  describe('empty and error states', () => {
    it('explains an empty search', () => {
      loadListing({ q: 'süt', page: '2' }, [], 0);

      const state = query('.listing-state');
      expect(state.getAttribute('role')).toBe('status');
      expect(state.textContent).toContain('Bu aramayla eşleşen ürün bulunamadı');
      expect(state.textContent).toContain(
        'Farklı bir ürün adı deneyebilir ya da filtreleri temizleyebilirsin.',
      );
      expect(element.querySelectorAll('app-product-preview').length).toBe(0);
    });

    it('names the filters when they are what emptied the result', () => {
      loadListing({ q: 'süt', page: '2', maxPrice: '5' }, [], 0);

      expect(query('.listing-state__title').textContent).toContain(
        'Bu filtrelerle eşleşen ürün bulunamadı',
      );
      expect(
        query<HTMLButtonElement>('.listing-state__action').textContent,
      ).toContain('Filtreleri temizle');
    });

    it('shows a retryable error and reloads on retry', () => {
      setQuery({ q: 'süt', page: '2' });
      expectSearch().flush('boom', { status: 500, statusText: 'Server Error' });
      fixture.detectChanges();

      expect(component.hasLoadError).toBeTrue();
      expect(query('.listing-state--error').getAttribute('role')).toBe('alert');

      query<HTMLButtonElement>('.listing-state__action').click();
      fixture.detectChanges();

      expect(component.hasLoadError).toBeFalse();
      expect(component.isLoading).toBeTrue();

      expectSearch().flush(page([product(7)], 25, 1));
      fixture.detectChanges();
      expect(queryAll('app-product-preview').length).toBe(1);
    });

    it('shows a skeleton while a navigation is loading', () => {
      setQuery({ q: 'yoğurt' });

      expect(component.isLoading).toBeTrue();
      expect(queryAll('.listing__skeleton').length).toBe(8);
      expect(element.querySelectorAll('app-product-preview').length).toBe(0);
    });
  });

  describe('sold-out results', () => {
    it('marks a sold-out result and cannot add it from a DOM click', () => {
      loadListing({ q: 'süt', page: '2' }, [product(11, 0), product(12, 4)], 25);
      flushImages();

      const cards = queryAll('app-product-preview');
      const soldOutStock = cards[0].querySelector('.product-card__stock') as HTMLElement;
      expect(soldOutStock.textContent).toContain('Tükendi');

      const soldOutButton = cards[0].querySelector(
        '.product-card__add',
      ) as HTMLButtonElement;
      expect(soldOutButton.disabled).toBeTrue();

      soldOutButton.click();
      fixture.detectChanges();

      const cartRequests = httpMock.match((request) =>
        request.url.includes('addProductToUserCart'),
      );
      expect(cartRequests.length).toBe(0);
    });

    it('leaves the sold-out result details reachable without a guessed category', () => {
      loadListing({ q: 'süt', page: '2' }, [product(11, 0)], 25);
      flushImages();

      const href =
        query<HTMLAnchorElement>('a.product-card__link').getAttribute('href') ?? '';
      expect(href.startsWith('/product/11?')).toBeTrue();
      expect(href).not.toContain('/category/');
    });

    it('offers the sold-out filter so a customer can ask for exactly those', () => {
      loadListing({ q: 'süt', page: '2' }, [product(11, 0)], 25);

      const soldOut = queryAll<HTMLButtonElement>('.listing__segment')[2];
      expect(soldOut.textContent?.trim()).toBe('Tükendi');
      soldOut.click();

      expect(navigateSpy).toHaveBeenCalledWith([], {
        relativeTo: jasmine.anything(),
        queryParams: { q: 'süt', availability: 'OUT_OF_STOCK' },
      });
    });
  });
});