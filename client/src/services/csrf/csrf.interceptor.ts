import {
  HttpErrorResponse,
  HttpEvent,
  HttpInterceptorFn,
  HttpRequest,
  HttpHandlerFn,
} from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable, throwError } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { shouldAttachCsrfHeader } from '../../app/config/backend.config';
import {
  CsrfTokenService,
  CsrfTokenUnavailableError,
} from './csrf-token.service';

/**
 * Identifies the stable error the API returns when Spring's CSRF filter
 * rejects a request. Only this code triggers a token refresh and replay;
 * ordinary authorization or domain `403` responses are returned unchanged.
 */
export function isCsrfFailure(error: unknown): error is HttpErrorResponse {
  if (!(error instanceof HttpErrorResponse) || error.status !== 403) {
    return false;
  }
  return readCsrfCode(error.error) === 'CSRF_INVALID';
}

/**
 * Reads the machine-readable error code from a response body. Angular leaves
 * the body as a string when a request uses `responseType: 'text'`, so string
 * bodies are parsed defensively; malformed or absent content yields
 * `undefined`, which never matches the CSRF contract.
 */
function readCsrfCode(body: unknown): unknown {
  if (typeof body === 'string') {
    try {
      const parsed = JSON.parse(body) as { code?: unknown } | null;
      return parsed?.code;
    } catch {
      return undefined;
    }
  }
  if (body && typeof body === 'object') {
    return (body as { code?: unknown }).code;
  }
  return undefined;
}

/**
 * Attaches the in-memory CSRF token to state-changing API requests, and
 * recovers from an expired/missing token by refreshing at most once and
 * replaying the request once. GET/HEAD and non-API requests are untouched.
 */
export const csrfInterceptor: HttpInterceptorFn = (req, next) => {
  const csrfTokenService = inject(CsrfTokenService);

  if (!shouldAttachCsrfHeader(req.url, req.method)) {
    return next(req);
  }

  return sendWithToken(req, next, csrfTokenService, true);
};

function sendWithToken(
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
  csrfTokenService: CsrfTokenService,
  allowRefresh: boolean,
): Observable<HttpEvent<unknown>> {
  return csrfTokenService.ensureToken().pipe(
    catchError((bootstrapError) =>
      throwError(() => new CsrfTokenUnavailableError(bootstrapError)),
    ),
    switchMap(({ token, headerName }) => {
      if (!token || !headerName) {
        return throwError(() => new CsrfTokenUnavailableError());
      }

      return next(req.clone({ setHeaders: { [headerName]: token } })).pipe(
        catchError((error) => {
          if (allowRefresh && isCsrfFailure(error)) {
            return csrfTokenService.refreshToken().pipe(
              catchError((refreshError) =>
                throwError(() => new CsrfTokenUnavailableError(refreshError)),
              ),
              switchMap(() => sendWithToken(req, next, csrfTokenService, false)),
            );
          }
          return throwError(() => error);
        }),
      );
    }),
  );
}
