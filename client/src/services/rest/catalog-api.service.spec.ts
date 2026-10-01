import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';

import { CatalogApiService } from './catalog-api.service';
import { IProductSearchResponse } from '../../interfaces/IProductSearchResponse';

describe('CatalogApiService.searchProducts', () => {
  let service: CatalogApiService;
  let httpMock: HttpTestingController;

  const page: IProductSearchResponse = {
    items: [
      { productId: 11, productName: 'Tam Süt', productPrice: 49.9, productCount: 4 },
    ],
    totalItems: 1,
    page: 0,
    size: 10,
    subcategories: [{ subCategoryName: 'Süt', productCount: 1 }],
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CatalogApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  function expectSearch() {
    return httpMock.expectOne(
      (request) => request.url === '/user/supply/searchProducts',
    );
  }

  it('reads the catalogue search endpoint with a GET', () => {
    service.searchProducts({ page: 0, size: 10 }).subscribe();

    const request = expectSearch();
    expect(request.request.method).toBe('GET');
    request.flush(page);
  });

  it('omits the defaults instead of spelling them out', () => {
    service.searchProducts({ page: 0, size: 10 }).subscribe();

    const request = expectSearch();
    expect(request.request.params.keys().sort()).toEqual(['page', 'size']);
    request.flush(page);
  });

  it('sends every control the listing offers in the endpoint casing', () => {
    service
      .searchProducts({
        q: 'çiçek',
        categoryId: 16,
        subcategory: 'Süt',
        availability: 'OUT_OF_STOCK',
        minPrice: 10.5,
        maxPrice: 50,
        discountedOnly: true,
        sort: 'PRICE_ASC',
        page: 2,
        size: 10,
      })
      .subscribe();

    const request = expectSearch();
    expect(request.request.params.get('q')).toBe('çiçek');
    expect(request.request.params.get('categoryId')).toBe('16');
    expect(request.request.params.get('subcategory')).toBe('Süt');
    expect(request.request.params.get('availability')).toBe('OUT_OF_STOCK');
    expect(request.request.params.get('minPrice')).toBe('10.5');
    expect(request.request.params.get('maxPrice')).toBe('50');
    expect(request.request.params.get('discountedOnly')).toBe('true');
    expect(request.request.params.get('sort')).toBe('PRICE_ASC');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('10');
    request.flush(page);
  });

  /**
   * The endpoint answers 400 for a subcategory without a category, so a search
   * listing that sent one would fail rather than search the catalogue.
   */
  it('never sends a subcategory without the category it belongs to', () => {
    service
      .searchProducts({ subcategory: 'Süt', page: 0, size: 10 })
      .subscribe();

    const request = expectSearch();
    expect(request.request.params.get('subcategory')).toBeNull();
    request.flush(page);
  });

  it('drops an empty term rather than searching for the empty string', () => {
    service.searchProducts({ q: '   ', page: 0, size: 10 }).subscribe();

    const request = expectSearch();
    expect(request.request.params.has('q')).toBeFalse();
    request.flush(page);
  });

  it('surfaces the page, the filtered total and the subcategory buckets', () => {
    let received: IProductSearchResponse | undefined;
    service
      .searchProducts({ categoryId: 3, page: 0, size: 10 })
      .subscribe((response) => (received = response));

    expectSearch().flush(page);

    expect(received?.totalItems).toBe(1);
    expect(received?.items[0].productName).toBe('Tam Süt');
    expect(received?.subcategories).toEqual([
      { subCategoryName: 'Süt', productCount: 1 },
    ]);
  });

  it('leaves the existing catalogue endpoints on their own paths', () => {
    service.getProductPageData(3, 0, 10).subscribe();
    service.getSubCategories(3).subscribe();
    service.getProductCountsFromCategory(3).subscribe();
    service.getProducstFromSubCategory('Süt', 0, 10).subscribe();

    const urls = httpMock.match(() => true).map((request) => request.request.url);
    expect(urls).toEqual([
      '/user/supply/getProductsFromCategory',
      '/user/supply/getSubCategories',
      '/user/supply/getProductCountsFromCategory',
      '/user/supply/getProductsFromSubcategory',
    ]);

    httpMock.match(() => true).forEach((request) => {
      if (!request.cancelled) {
        request.flush(null as never);
      }
    });
  });
});