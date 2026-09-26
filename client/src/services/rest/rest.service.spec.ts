import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';

import { RestService } from './rest.service';

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
});
