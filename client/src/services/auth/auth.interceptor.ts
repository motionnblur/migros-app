import { HttpInterceptorFn } from '@angular/common/http';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const url = req.url;

  let token: string | null = null;

  if (url.includes('/admin/panel/')) {
    token = localStorage.getItem('admin-token');
  } else if (url.includes('/admin/supply/')) {
    token = localStorage.getItem('admin-token');
  } else if (url.includes('/user/')) {
    token = localStorage.getItem('token');
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
