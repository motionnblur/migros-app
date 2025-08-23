import { HttpInterceptorFn } from '@angular/common/http';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const url = req.url;

  let token: string | null = null;

  if (url.includes('/admin/')) {
    token = localStorage.getItem('admin-token');
  } else if (url.includes('/user/')) {
    token = localStorage.getItem('user-token');
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
