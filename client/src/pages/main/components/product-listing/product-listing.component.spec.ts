import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import {
  ActivatedRoute,
  ParamMap,
  convertToParamMap,
  provideRouter,
} from '@angular/router';
import { BehaviorSubject } from 'rxjs';

import { AuthService } from '../../../../services/auth/auth.service';
import { ListingFilterState, defaultListingFilters } from '../../helpers/catalog-listing-state';
import { ProductListingComponent, ProductListingRequest } from './product-listing.component';

describe('ProductListingComponent', () => {
  let component: ProductListingComponent;
  let fixture: ComponentFixture<ProductListingComponent>;
  let httpMock: HttpTestingController;
  let element: HTMLElement;
  let queryParamMap: BehaviorSubject<ParamMap>;

  function product(id: number, count = 3, price = 25) {
    return { productId: id, productName: `Ürün ${id}`, productPrice: price, productCount: count };
  }

  function setFilters(overrides: Partial<ListingFilterState>): void {
    fixture.componentRef.setInput('filters', {
      ...defaultListingFilters(),
      ...overrides,
    });
    fixture.detectChanges();
  }

  function setItems(items: ReturnType<typeof product>[]): void {
    fixture.componentRef.setInput('items', items);
    fixture.detectChanges();
  }

  function query<T extends Element = HTMLElement>(selector: string): T {
    return element.querySelector(selector) as T;
  }

  function queryAll<T extends Element = HTMLElement>(selector: string): T[] {
    return Array.from(element.querySelectorAll(selector) as NodeListOf<T>);
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

  function cartRequests() {
    return httpMock.match((request) => request.url.includes('addProductToUserCart'));
  }

  beforeEach(async () => {
    queryParamMap = new BehaviorSubject<ParamMap>(convertToParamMap({}));

    await TestBed.configureTestingModule({
      imports: [ProductListingComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: AuthService,
          useValue: {
            isLoggedIn: jasmine.createSpy('isLoggedIn').and.returnValue(true),
            getUserMail: jasmine.createSpy('getUserMail').and.returnValue('ayse@example.com'),
          },
        },
        {
          provide: ActivatedRoute,
          useValue: { queryParamMap, snapshot: { queryParams: {}, paramMap: convertToParamMap({}) } },
        },
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);

    fixture = TestBed.createComponent(ProductListingComponent);
    component = fixture.componentInstance;
    element = fixture.nativeElement as HTMLElement;
    fixture.componentRef.setInput('heading', 'Süt, Kahvaltılık');
    fixture.componentRef.setInput('items', []);
    fixture.componentRef.setInput('totalItems', 0);
    fixture.componentRef.setInput('filters', defaultListingFilters());
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

  it('exposes every filter control in Turkish', () => {
    const availability = queryAll('.listing__segment').map((button) => button.textContent?.trim());
    expect(availability).toEqual(['Tümü', 'Stokta', 'Tükendi']);

    const sorts = Array.from(
      query<HTMLSelectElement>('#listing-sort').querySelectorAll('option'),
    ).map((option) => option.textContent?.trim());
    expect(sorts).toEqual([
      'Varsayılan sıralama',
      'Fiyat: düşükten yükseğe',
      'Fiyat: yüksekten düşüğe',
    ]);

    expect(query('.listing__toggle').textContent).toContain('Yalnızca indirimliler');
    expect(query('.listing__price-field').getAttribute('placeholder')).toBe('En düşük');
    expect(
      queryAll<HTMLInputElement>('.listing__price-field')[1].getAttribute('placeholder'),
    ).toBe('En yüksek');
  });

  it('marks All as the selected availability until something else is chosen', () => {
    expect(queryAll('.listing__segment')[0].getAttribute('aria-pressed')).toBe('true');
    expect(component.filters.availability).toBe('ALL');
  });

  it('reports the server total as the result count and sizes the pages from it', () => {
    fixture.componentRef.setInput('totalItems', 42);
    fixture.detectChanges();

    expect(query('.listing__count').textContent).toContain('42 ürün');

    fixture.componentRef.setInput('pageCount', 5);
    fixture.detectChanges();
    expect(query('app-product-page-switcher')).toBeTruthy();
  });

  it('hides the paginator for a single page result', () => {
    setItems([product(1)]);
    fixture.componentRef.setInput('pageCount', 1);
    fixture.detectChanges();

    expect(query('app-product-page-switcher')).toBeNull();
  });

  describe('filter requests', () => {
    let requests: ProductListingRequest[];

    beforeEach(() => {
      requests = [];
      component.requestChange.subscribe((request) => requests.push(request));
    });

    it('resets to the first page when availability changes', () => {
      queryAll<HTMLButtonElement>('.listing__segment')[1].click();

      expect(requests).toEqual([
        {
          filters: { ...defaultListingFilters(), availability: 'IN_STOCK' },
          page: 1,
        },
      ]);
    });

    it('resets to the first page when the sort changes', () => {
      const select = query<HTMLSelectElement>('#listing-sort');
      select.value = 'PRICE_ASC';
      select.dispatchEvent(new Event('change'));
      fixture.detectChanges();

      expect(requests).toEqual([
        {
          filters: { ...defaultListingFilters(), sort: 'PRICE_ASC' },
          page: 1,
        },
      ]);
    });

    it('toggles the discounted-only control', () => {
      query<HTMLButtonElement>('.listing__toggle').click();

      expect(requests[0].filters.discountedOnly).toBeTrue();
      expect(requests[0].page).toBe(1);
    });

    it('applies typed price bounds with the Turkish decimal comma', () => {
      setFilters({ minPrice: '' });
      const fields = queryAll<HTMLInputElement>('.listing__price-field');
      fields[0].value = '10,50';
      fields[0].dispatchEvent(new Event('input'));
      fields[1].value = '50';
      fields[1].dispatchEvent(new Event('input'));
      fixture.detectChanges();

      query<HTMLButtonElement>('.listing__price-apply').click();

      expect(requests).toEqual([
        {
          filters: { ...defaultListingFilters(), minPrice: '10,50', maxPrice: '50' },
          page: 1,
        },
      ]);
    });

    it('refuses an inverted price pair instead of asking the endpoint for a 400', () => {
      const fields = queryAll<HTMLInputElement>('.listing__price-field');
      fields[0].value = '90';
      fields[0].dispatchEvent(new Event('input'));
      fields[1].value = '10';
      fields[1].dispatchEvent(new Event('input'));
      fixture.detectChanges();

      query<HTMLButtonElement>('.listing__price-apply').click();
      fixture.detectChanges();

      expect(requests).toEqual([]);
      const error = query('.listing__price-error');
      expect(error.textContent).toContain(
        'En düşük fiyat, en yüksek fiyattan büyük olamaz.',
      );
      expect(error.getAttribute('role')).toBe('alert');
    });

    it('keeps a typed draft out of the URL until the customer applies it', () => {
      const fields = queryAll<HTMLInputElement>('.listing__price-field');
      fields[0].value = '25';
      fields[0].dispatchEvent(new Event('input'));
      fixture.detectChanges();

      expect(component.filters.minPrice).toBe('');
      expect(requests).toEqual([]);
      expect(fields[0].value).toBe('25');
    });

    it('adopts the URL bounds when the customer is not mid-edit', () => {
      setFilters({ minPrice: '15', maxPrice: '45' });

      const fields = queryAll<HTMLInputElement>('.listing__price-field');
      expect(fields[0].value).toBe('15');
      expect(fields[1].value).toBe('45');
    });

    it('asks for the requested page when the paginator is used', () => {
      setFilters({ sort: 'PRICE_ASC' });
      fixture.componentRef.setInput('pageCount', 4);
      fixture.componentRef.setInput('page', 1);
      fixture.detectChanges();

      const nextButton = Array.from(
        element.querySelectorAll<HTMLButtonElement>('.page-switcher__button'),
      ).find((button) => button.getAttribute('aria-label')?.startsWith('Sonraki sayfa'));

      expect(nextButton).toBeTruthy();
      nextButton?.click();
      fixture.detectChanges();

      expect(requests).toEqual([
        { filters: { ...defaultListingFilters(), sort: 'PRICE_ASC' }, page: 2 },
      ]);
    });
  });

  describe('applied filter chips', () => {
    it('renders nothing while every control is at its default', () => {
      expect(query('.listing__chips')).toBeNull();
      expect(component.hasChips).toBeFalse();
    });

    it('renders one removable chip per applied control', () => {
      setFilters({
        availability: 'IN_STOCK',
        minPrice: '10',
        maxPrice: '50',
        discountedOnly: true,
        sort: 'PRICE_DESC',
      });

      const chips = queryAll('.listing__chip-text').map((chip) => chip.textContent?.trim());
      expect(chips).toEqual([
        'Bulunabilirlik: Stokta',
        'Fiyat: 10 TL – 50 TL',
        'Yalnızca indirimli ürünler',
        'Fiyat: yüksekten düşüğe',
      ]);
    });

    it('offers a clear-all action and clears only the filter group', () => {
      setFilters({ availability: 'OUT_OF_STOCK', discountedOnly: true });
      const requests: ProductListingRequest[] = [];
      component.requestChange.subscribe((request) => requests.push(request));

      query<HTMLButtonElement>('.listing__clear-all').click();

      expect(requests).toEqual([
        { filters: defaultListingFilters(), page: 1 },
      ]);
    });

    it('resets exactly the control a chip names', () => {
      setFilters({
        availability: 'OUT_OF_STOCK',
        minPrice: '10',
        maxPrice: '50',
        discountedOnly: true,
        sort: 'PRICE_DESC',
      });
      const requests: ProductListingRequest[] = [];
      component.requestChange.subscribe((request) => requests.push(request));

      const availabilityChip = queryAll<HTMLButtonElement>('.listing__chip-remove').find(
        (button) => button.getAttribute('aria-label')?.includes('Bulunabilirlik'),
      ) as HTMLButtonElement;
      availabilityChip.click();

      expect(requests[0].filters).toEqual({
        ...defaultListingFilters(),
        minPrice: '10',
        maxPrice: '50',
        discountedOnly: true,
        sort: 'PRICE_DESC',
      });
      expect(requests[0].page).toBe(1);
    });

    it('clears both price bounds from one chip', () => {
      setFilters({ minPrice: '10', maxPrice: '50' });
      const requests: ProductListingRequest[] = [];
      component.requestChange.subscribe((request) => requests.push(request));

      const priceChip = queryAll<HTMLButtonElement>('.listing__chip-remove').find(
        (button) => button.getAttribute('aria-label')?.includes('Fiyat'),
      ) as HTMLButtonElement;
      priceChip.click();

      expect(requests[0].filters.minPrice).toBe('');
      expect(requests[0].filters.maxPrice).toBe('');
    });

    it('offers the subcategory as its own removable chip', () => {
      fixture.componentRef.setInput('subCategoryName', 'Peynir');
      fixture.detectChanges();

      const cleared: number[] = [];
      component.subCategoryCleared.subscribe(() => cleared.push(1));

      expect(query('.listing__chip-text').textContent).toContain(
        'Alt kategori: Peynir',
      );
      query<HTMLButtonElement>('.listing__chip-remove').click();

      expect(cleared.length).toBe(1);
    });
  });

  describe('states', () => {
    it('renders loading skeletons instead of rows', () => {
      fixture.componentRef.setInput('isLoading', true);
      setItems([product(1)]);
      fixture.detectChanges();

      expect(queryAll('.listing__skeleton').length).toBe(8);
      expect(element.querySelectorAll('app-product-preview').length).toBe(0);
      expect(query('.listing__content').getAttribute('aria-busy')).toBe('true');
    });

    it('shows a retryable error state and re-emits the retry', () => {
      fixture.componentRef.setInput('hasLoadError', true);
      fixture.detectChanges();

      const alert = query('.listing-state--error');
      expect(alert.getAttribute('role')).toBe('alert');

      let retried = 0;
      component.retryRequested.subscribe(() => retried++);
      query<HTMLButtonElement>('.listing-state__action').click();

      expect(retried).toBe(1);
    });

    it('explains an empty result and offers a way out of the filters', () => {
      fixture.componentRef.setInput('emptyTitle', 'Bu aramayla eşleşen ürün bulunamadı');
      fixture.componentRef.setInput('emptyText', 'Farklı bir ürün adı deneyebilirsin.');
      setFilters({ availability: 'IN_STOCK' });
      fixture.detectChanges();

      const state = query('.listing-state');
      expect(state.getAttribute('role')).toBe('status');
      expect(state.textContent).toContain('Bu aramayla eşleşen ürün bulunamadı');
      expect(state.textContent).toContain('Farklı bir ürün adı deneyebilirsin.');
      expect(
        query<HTMLButtonElement>('.listing-state__action').textContent,
      ).toContain('Filtreleri temizle');
    });
  });

  describe('sold-out products', () => {
    it('marks a sold-out card and refuses an add from a real DOM click', () => {
      setItems([product(11, 0), product(12, 5)]);
      flushImages();

      const cards = queryAll('app-product-preview');
      expect(cards.length).toBe(2);

      const soldOutStock = cards[0].querySelector('.product-card__stock') as HTMLElement;
      expect(soldOutStock.textContent).toContain('Tükendi');

      const soldOutButton = cards[0].querySelector(
        '.product-card__add',
      ) as HTMLButtonElement;
      expect(soldOutButton.disabled).toBeTrue();
      expect(soldOutButton.getAttribute('aria-label')).toContain('stokta yok');

      // A DOM click, not a direct call to the component method: a disabled button
      // that a handler would still reach proves nothing about what a customer can
      // actually do. The browser does not even deliver the event.
      soldOutButton.click();
      fixture.detectChanges();

      expect(cartRequests().length).toBe(0);
      expect(cards[0].querySelector('.product-card__feedback')).toBeNull();
    });

    it('still leaves the sold-out product details reachable', () => {
      fixture.componentRef.setInput('linkCategoryId', null);
      setItems([product(11, 0)]);
      flushImages();

      const link = query<HTMLAnchorElement>('a.product-card__link');
      expect(link.getAttribute('href')).toBe('/product/11');
      expect(
        query<HTMLButtonElement>('.product-card__add').disabled,
      ).toBeTrue();
    });
  });

  it('links a category scoped listing into that category detail route', () => {
    fixture.componentRef.setInput('linkCategoryId', 3);
    setItems([product(11)]);
    flushImages();

    expect(query<HTMLAnchorElement>('a.product-card__link').getAttribute('href')).toBe(
      '/category/3/product/11',
    );
  });

  it('carries the whole listing state onto a card link', () => {
    queryParamMap.next(
      convertToParamMap({
        q: 'çiçek',
        availability: 'IN_STOCK',
        minPrice: '10',
        discounted: 'true',
        sort: 'PRICE_ASC',
        page: '2',
      }),
    );
    setItems([product(11)]);
    flushImages();

    const href = query<HTMLAnchorElement>('a.product-card__link').getAttribute('href') ?? '';
    expect(href.startsWith('/product/11?')).toBeTrue();
    expect(href).toContain('q=%C3%A7i%C3%A7ek');
    expect(href).toContain('availability=IN_STOCK');
    expect(href).toContain('minPrice=10');
    expect(href).toContain('discounted=true');
    expect(href).toContain('sort=PRICE_ASC');
    expect(href).toContain('page=2');
  });
});