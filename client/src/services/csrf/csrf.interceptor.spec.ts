import { TestBed } from '@angular/core/testing';
import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';

import { csrfInterceptor } from './csrf.interceptor';
import { CsrfTokenUnavailableError } from './csrf-token.service';

describe('csrfInterceptor', () => {
  let client: HttpClient;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([csrfInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    client = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('bootstraps a token and attaches it to state-changing API requests', () => {
    client.post('/user/login', { userMail: 'a@b.com' }).subscribe();

    httpMock
      .expectOne('/csrf')
      .flush({ token: 'token-123', headerName: 'X-XSRF-TOKEN' });

    const login = httpMock.expectOne('/user/login');
    expect(login.request.method).toBe('POST');
    expect(login.request.headers.get('X-XSRF-TOKEN')).toBe('token-123');
    login.flush({});
  });

  it('adds the token to DELETE requests as well', () => {
    client.delete('/user/supply/cancelOrder').subscribe();

    httpMock
      .expectOne('/csrf')
      .flush({ token: 'token-123', headerName: 'X-XSRF-TOKEN' });

    const cancel = httpMock.expectOne('/user/supply/cancelOrder');
    expect(cancel.request.method).toBe('DELETE');
    expect(cancel.request.headers.get('X-XSRF-TOKEN')).toBe('token-123');
    cancel.flush({});
  });

  it('does not attach the token or bootstrap for GET requests', () => {
    client.get('/user/supply/getProductData').subscribe();

    const request = httpMock.expectOne('/user/supply/getProductData');
    expect(request.request.headers.has('X-XSRF-TOKEN')).toBe(false);
    httpMock.expectNone('/csrf');
    request.flush([]);
  });

  it('does not attach the token or bootstrap for HEAD requests', () => {
    client.head('/user/session').subscribe();

    const request = httpMock.expectOne('/user/session');
    expect(request.request.headers.has('X-XSRF-TOKEN')).toBe(false);
    httpMock.expectNone('/csrf');
    request.flush(null);
  });

  it('leaves non-API requests untouched', () => {
    client.get('https://external.example/data').subscribe();

    const request = httpMock.expectOne('https://external.example/data');
    expect(request.request.headers.has('X-XSRF-TOKEN')).toBe(false);
    httpMock.expectNone('/csrf');
    request.flush({});
  });

  it('fails closed when the token bootstrap fails', () => {
    let error: unknown = null;
    client.post('/user/login', {}).subscribe({
      error: (err) => {
        error = err;
      },
    });

    httpMock.expectOne('/csrf').error(new ProgressEvent('error'));

    httpMock.expectNone('/user/login');
    expect(error).toBeInstanceOf(CsrfTokenUnavailableError);
  });

  it('does not send a state-changing request without a token', () => {
    let error: unknown = null;
    client.post('/user/login', {}).subscribe({
      error: (err) => {
        error = err;
      },
    });

    httpMock.expectOne('/csrf').flush({ token: '', headerName: '' });

    httpMock.expectNone('/user/login');
    expect(error).toBeInstanceOf(CsrfTokenUnavailableError);
  });

  it('refreshes once and replays after a CSRF 403', () => {
    let completed = false;
    client.post('/user/login', {}).subscribe({ next: () => (completed = true) });

    httpMock
      .expectOne('/csrf')
      .flush({ token: 'expired-token', headerName: 'X-XSRF-TOKEN' });

    const firstAttempt = httpMock.expectOne('/user/login');
    expect(firstAttempt.request.headers.get('X-XSRF-TOKEN')).toBe('expired-token');
    firstAttempt.flush(
      { code: 'CSRF_INVALID' },
      { status: 403, statusText: 'Forbidden' },
    );

    httpMock
      .expectOne('/csrf')
      .flush({ token: 'fresh-token', headerName: 'X-XSRF-TOKEN' });

    const replay = httpMock.expectOne('/user/login');
    expect(replay.request.headers.get('X-XSRF-TOKEN')).toBe('fresh-token');
    replay.flush({});

    expect(completed).toBe(true);
    httpMock.expectNone('/csrf');
    httpMock.expectNone('/user/login');
  });

  it('never retries a mutation more than once', () => {
    let error: unknown = null;
    client.post('/user/login', {}).subscribe({
      error: (err) => {
        error = err;
      },
    });

    httpMock
      .expectOne('/csrf')
      .flush({ token: 'expired-token', headerName: 'X-XSRF-TOKEN' });

    httpMock
      .expectOne('/user/login')
      .flush(
        { code: 'CSRF_INVALID' },
        { status: 403, statusText: 'Forbidden' },
      );

    httpMock
      .expectOne('/csrf')
      .flush({ token: 'fresh-token', headerName: 'X-XSRF-TOKEN' });

    httpMock
      .expectOne('/user/login')
      .flush(
        { code: 'CSRF_INVALID' },
        { status: 403, statusText: 'Forbidden' },
      );

    httpMock.expectNone('/csrf');
    httpMock.expectNone('/user/login');
    expect((error as { status?: number })?.status).toBe(403);
  });

  it('does not refresh or replay an ordinary 403', () => {
    let error: HttpErrorResponse | null = null;
    client.post('/user/login', {}).subscribe({
      error: (err) => {
        error = err;
      },
    });

    httpMock
      .expectOne('/csrf')
      .flush({ token: 'token-123', headerName: 'X-XSRF-TOKEN' });

    httpMock
      .expectOne('/user/login')
      .flush(
        { message: 'Not allowed' },
        { status: 403, statusText: 'Forbidden' },
      );

    expect((error as HttpErrorResponse | null)?.status).toBe(403);
    httpMock.expectNone('/csrf');
    httpMock.expectNone('/user/login');
  });

  it('does not replay a 403 with malformed error content', () => {
    let error: HttpErrorResponse | null = null;
    client.post('/user/login', {}).subscribe({
      error: (err) => {
        error = err;
      },
    });

    httpMock
      .expectOne('/csrf')
      .flush({ token: 'token-123', headerName: 'X-XSRF-TOKEN' });

    httpMock
      .expectOne('/user/login')
      .flush('not-json', { status: 403, statusText: 'Forbidden' });

    expect((error as HttpErrorResponse | null)?.status).toBe(403);
    httpMock.expectNone('/csrf');
    httpMock.expectNone('/user/login');
  });
});
