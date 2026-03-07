import { HttpInterceptorFn } from '@angular/common/http';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.url.startsWith('http://localhost:8080')) {
    req = req.clone({ withCredentials: true });
  }

  return next(req);
};
