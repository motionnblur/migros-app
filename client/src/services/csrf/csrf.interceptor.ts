import {
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
          if (allowRefresh && error?.status === 403) {
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
