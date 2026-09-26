import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideZoneChangeDetection,
} from '@angular/core';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { authInterceptor } from '../services/auth/auth.interceptor';
import { csrfInterceptor } from '../services/csrf/csrf.interceptor';
import {
  CsrfTokenService,
  initializeCsrfToken,
} from '../services/csrf/csrf-token.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor, csrfInterceptor])),
    provideAppInitializer(() =>
      initializeCsrfToken(inject(CsrfTokenService))(),
    ),
    provideAnimationsAsync(),
  ],
};
