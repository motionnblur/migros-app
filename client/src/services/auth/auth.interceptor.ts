import { HttpInterceptorFn } from '@angular/common/http';

const BACKEND_ORIGIN = 'https://migros-app.onrender.com';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.url.startsWith(BACKEND_ORIGIN)) {
    req = req.clone({ withCredentials: true });
  }

  return next(req);
};
