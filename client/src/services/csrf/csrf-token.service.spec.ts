import { TestBed } from '@angular/core/testing';
import { provideHttpClient, HttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';

import {
  CsrfTokenService,
  initializeCsrfToken,
} from './csrf-token.service';

describe('CsrfTokenService', () => {
  let service: CsrfTokenService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CsrfTokenService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('fetches the token from /csrf with credentials and stores it in memory', () => {
    service.ensureToken().subscribe();

    const request = httpMock.expectOne('/csrf');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBe(true);
    request.flush({ token: 'token-123', headerName: 'X-XSRF-TOKEN' });

    expect(service.getToken()).toBe('token-123');
    expect(service.getHeaderName()).toBe('X-XSRF-TOKEN');
    expect(service.hasToken()).toBe(true);
  });

  it('does not refetch when a token is already cached', () => {
    service.ensureToken().subscribe();
    httpMock
      .expectOne('/csrf')
      .flush({ token: 'token-123', headerName: 'X-XSRF-TOKEN' });

    let secondToken = '';
    service.ensureToken().subscribe((response) => {
      secondToken = response.token;
    });

    httpMock.expectNone('/csrf');
    expect(secondToken).toBe('token-123');
  });

  it('refreshes the token from /csrf on demand', () => {
    service.ensureToken().subscribe();
    httpMock
      .expectOne('/csrf')
      .flush({ token: 'token-123', headerName: 'X-XSRF-TOKEN' });

    service.refreshToken().subscribe();
    httpMock
      .expectOne('/csrf')
      .flush({ token: 'token-456', headerName: 'X-XSRF-TOKEN' });

    expect(service.getToken()).toBe('token-456');
  });

  it('shares one request between concurrent ensureToken subscribers', () => {
    const received: string[] = [];
    service.ensureToken().subscribe((response) => received.push(response.token));
    service.ensureToken().subscribe((response) => received.push(response.token));

    const requests = httpMock.match('/csrf');
    expect(requests.length).toBe(1);
    requests[0].flush({ token: 'token-123', headerName: 'X-XSRF-TOKEN' });

    expect(received).toEqual(['token-123', 'token-123']);
    httpMock.expectNone('/csrf');
  });

  it('shares one request between concurrent refreshToken subscribers', () => {
    service.ensureToken().subscribe();
    httpMock
      .expectOne('/csrf')
      .flush({ token: 'token-123', headerName: 'X-XSRF-TOKEN' });

    const received: string[] = [];
    service.refreshToken().subscribe((response) => received.push(response.token));
    service.refreshToken().subscribe((response) => received.push(response.token));

    const requests = httpMock.match('/csrf');
    expect(requests.length).toBe(1);
    requests[0].flush({ token: 'token-456', headerName: 'X-XSRF-TOKEN' });

    expect(received).toEqual(['token-456', 'token-456']);
    expect(service.getToken()).toBe('token-456');
  });

  it('allows a new request after an in-flight request completes', () => {
    service.refreshToken().subscribe();
    httpMock
      .expectOne('/csrf')
      .flush({ token: 'token-123', headerName: 'X-XSRF-TOKEN' });

    service.refreshToken().subscribe();
    const requests = httpMock.match('/csrf');
    expect(requests.length).toBe(1);
    requests[0].flush({ token: 'token-456', headerName: 'X-XSRF-TOKEN' });

    expect(service.getToken()).toBe('token-456');
  });

  it('allows recovery after a shared request errors', () => {
    let firstError: unknown = null;
    service.ensureToken().subscribe({
      error: (error) => {
        firstError = error;
      },
    });

    httpMock.expectOne('/csrf').error(new ProgressEvent('error'));
    expect(firstError).not.toBeNull();

    service.ensureToken().subscribe();
    const requests = httpMock.match('/csrf');
    expect(requests.length).toBe(1);
    requests[0].flush({ token: 'token-123', headerName: 'X-XSRF-TOKEN' });

    expect(service.getToken()).toBe('token-123');
  });

  it('application initialization fetches /csrf with credentials', async () => {
    const initializer = initializeCsrfToken(service);

    const initialized = initializer();
    const request = httpMock.expectOne('/csrf');
    expect(request.request.withCredentials).toBe(true);
    request.flush({ token: 'init-token', headerName: 'X-XSRF-TOKEN' });

    await initialized;
    expect(service.getToken()).toBe('init-token');
  });

  it('application initialization stays recoverable when the bootstrap fails', async () => {
    const initializer = initializeCsrfToken(service);

    const initialized = initializer();
    httpMock.expectOne('/csrf').error(new ProgressEvent('error'));

    await expectAsync(initialized).toBeResolved();
    expect(service.hasToken()).toBe(false);
  });
});
