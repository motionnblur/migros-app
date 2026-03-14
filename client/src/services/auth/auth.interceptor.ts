import { HttpInterceptorFn } from '@angular/common/http';
import { shouldAttachCredentials } from '../../app/config/backend.config';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (shouldAttachCredentials(req.url)) {
    req = req.clone({ withCredentials: true });
  }

  return next(req);
};
