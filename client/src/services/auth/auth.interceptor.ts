import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const url = req.url;

  let token: string | null = null;
  let isAdminRequest = false;

  if (url.includes('/admin/panel/') || url.includes('/admin/supply/')) {
    token = authService.getAdminToken();
    isAdminRequest = true;
  } else if (url.includes('/user/')) {
    token = authService.getToken();
  }

  if (token && authService.isTokenExpired(token)) {
    if (isAdminRequest) {
      authService.logoutAdmin();
    } else {
      authService.logout();
    }
    token = null;
  }

  if (token) {
    req = req.clone({
      setHeaders: {
        Authorization: `Bearer ${token}`,
      },
    });
  }

  return next(req);
};
