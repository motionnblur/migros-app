import { HttpClient, HttpBackend } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { finalize, shareReplay, tap } from 'rxjs/operators';
import { apiUrl } from '../../app/config/backend.config';

export interface CsrfTokenResponse {
  token: string;
  headerName: string;
}

/**
 * Raised when a state-changing request cannot be sent with a valid CSRF token.
 * The request is never issued unprotected; a later mutation retries the token
 * bootstrap, so the failure stays recoverable.
 */
export class CsrfTokenUnavailableError extends Error {
  constructor(public readonly reason?: unknown) {
    super(
      'CSRF token is unavailable; refusing to send an unprotected state-changing request.',
    );
    this.name = 'CsrfTokenUnavailableError';
  }
}

/**
 * In-memory CSRF token lifecycle.
 *
 * The API and the SPA are different origins, so the API's `XSRF-TOKEN` cookie
 * is not readable from the browser on the SPA origin. This service fetches the
 * token from the JSON `/csrf` endpoint with credentials (the browser still
 * replays the cookie on later requests) and keeps it only in memory.
 *
 * The token request uses an `HttpClient` built directly from `HttpBackend` so it
 * bypasses the interceptor chain and cannot recurse into CSRF handling.
 */
@Injectable({
  providedIn: 'root',
})
export class CsrfTokenService {
  private token: string | null = null;
  private headerName: string | null = null;
  private inFlight: Observable<CsrfTokenResponse> | null = null;
  private readonly http: HttpClient;

  constructor(httpBackend: HttpBackend) {
    this.http = new HttpClient(httpBackend);
  }

  getToken(): string | null {
    return this.token;
  }

  getHeaderName(): string | null {
    return this.headerName;
  }

  hasToken(): boolean {
    return !!this.token && !!this.headerName;
  }

  ensureToken(): Observable<CsrfTokenResponse> {
    if (this.hasToken()) {
      return of({
        token: this.token as string,
        headerName: this.headerName as string,
      });
    }
    return this.fetchToken();
  }

  refreshToken(): Observable<CsrfTokenResponse> {
    return this.fetchToken();
  }

  private fetchToken(): Observable<CsrfTokenResponse> {
    if (this.inFlight) {
      return this.inFlight;
    }

    const request$ = this.http
      .get<CsrfTokenResponse>(apiUrl('/csrf'), { withCredentials: true })
      .pipe(
        tap((response) => {
          this.token = response?.token ?? null;
          this.headerName = response?.headerName ?? null;
        }),
        finalize(() => {
          this.inFlight = null;
        }),
      );

    // A single cold HTTP request shared by every concurrent subscriber. It is
    // cached so late subscribers during the same fetch receive the same result,
    // while cleanup on the upstream completion/error clears the in-flight slot
    // exactly once so a later fetch can start. Errors are never retained.
    this.inFlight = request$.pipe(
      shareReplay({ bufferSize: 1, refCount: false }),
    );

    return this.inFlight;
  }
}

/**
 * Application initializer: obtain the first token before the app runs. A failed
 * bootstrap is intentionally swallowed so the app still boots; the CSRF
 * interceptor retries on demand and fails closed until a token is available.
 */
export function initializeCsrfToken(
  csrfTokenService: CsrfTokenService,
): () => Promise<void> {
  return () =>
    new Promise<void>((resolve) => {
      csrfTokenService.ensureToken().subscribe({
        next: () => resolve(),
        error: () => resolve(),
      });
    });
}
