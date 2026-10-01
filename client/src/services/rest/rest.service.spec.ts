import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';

import { RestService } from './rest.service';
import { IProductData } from '../../interfaces/IProductData';

describe('RestService state-changing requests', () => {
  let service: RestService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(RestService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('updateOrderStatus uses POST, not GET', () => {
    service.updateOrderStatus(1, 'SHIPPED').subscribe();

    const request = httpMock.expectOne(
      (candidate) =>
        candidate.url === '/admin/panel/updateOrderStatus' &&
        candidate.params.get('orderId') === '1' &&
        candidate.params.get('status') === 'SHIPPED',
    );

    expect(request.request.method).toBe('POST');
    request.flush('', { status: 200, statusText: 'OK' });
  });

  it('addProductToUserCart uses POST, not GET', () => {
    service.addProductToUserCart(42).subscribe();

    const request = httpMock.expectOne(
      (candidate) =>
        candidate.url === '/user/supply/addProductToUserCart' &&
        candidate.params.get('productId') === '42',
    );

    expect(request.request.method).toBe('POST');
    request.flush('', { status: 200, statusText: 'OK' });
  });

  it('getVerifyUser uses POST with the userMail query parameter', () => {
    service.getVerifyUser('user@example.com').subscribe();

    const request = httpMock.expectOne(
      (candidate) =>
        candidate.url === '/user/verifyUserMail' &&
        candidate.params.get('userMail') === 'user@example.com',
    );

    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    request.flush('', { status: 200, statusText: 'OK' });

    httpMock.expectNone(
      (candidate) =>
        candidate.url === '/user/verifyUserMail' &&
        candidate.method === 'GET',
    );
  });

  it('updateProductCountInUserCart uses POST, not GET', () => {
    service.updateProductCountInUserCart(42, 3).subscribe();

    const request = httpMock.expectOne(
      (candidate) =>
        candidate.url === '/user/supply/updateProductCountInUserCart' &&
        candidate.params.get('productId') === '42' &&
        candidate.params.get('count') === '3',
    );

    expect(request.request.method).toBe('POST');
    request.flush('', { status: 200, statusText: 'OK' });
  });

  it('updateProductData reaches the multipart update endpoint with the expected version', () => {
    service
      .updateProductData({
        adminId: 1,
        productId: 42,
        productName: 'Milk',
        subCategoryName: 'Dairy',
        productPrice: 12.5,
        productCount: 7,
        productDiscount: 3,
        productDescription: 'Fresh milk',
        selectedImage: undefined,
        categoryValue: 5,
        expectedVersion: 4,
      })
      .subscribe();

    const request = httpMock.expectOne(
      (candidate) => candidate.url === '/admin/panel/updateProduct',
    );

    expect(request.request.method).toBe('POST');
    const body = request.request.body as FormData;
    expect(body.get('expectedVersion')).toBe('4');
    request.flush('File uploaded successfully', { status: 200, statusText: 'OK' });
  });

  it('getProductData still reads the catalog endpoint and passes the version through', () => {
    let received: IProductData | undefined;
    service.getProductData(42).subscribe((data) => (received = data));

    const request = httpMock.expectOne(
      (candidate) =>
        candidate.url === '/user/supply/getProductDataWithProductId' &&
        candidate.params.get('productId') === '42',
    );

    request.flush({
      productName: 'Milk',
      subCategoryName: 'Dairy',
      productPrice: 12.5,
      productCount: 7,
      productDiscount: 3,
      productDescription: 'Fresh milk',
      productCategoryId: 5,
      productVersion: 9,
    });

    expect(received?.productVersion).toBe(9);
  });

  /**
   * The facade stays a facade: `searchProducts` must reach the catalogue domain
   * client rather than opening a second HTTP path for the same operation.
   */
  it('searchProducts delegates to the catalogue domain client', () => {
    service
      .searchProducts({ q: 'süt', availability: 'IN_STOCK', page: 0, size: 10 })
      .subscribe();

    const request = httpMock.expectOne(
      (candidate) => candidate.url === '/user/supply/searchProducts',
    );

    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('q')).toBe('süt');
    expect(request.request.params.get('availability')).toBe('IN_STOCK');
    request.flush({
      items: [],
      totalItems: 0,
      page: 0,
      size: 10,
      subcategories: [],
    });
  });
});
