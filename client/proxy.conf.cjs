const target = process.env.NG_PROXY_TARGET || 'http://localhost:8080';

// `/admin` is both an API prefix and an SPA route. Serve the Angular shell for
// top-level browser navigations (Accept: text/html) so a direct load or refresh
// of http://localhost:4200/admin works; let XHR/API calls continue to the backend.
function serveSpaForHtmlNavigation(req) {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    return undefined;
  }
  const accept = req.headers.accept || '';
  return accept.includes('text/html') ? '/index.html' : undefined;
}

module.exports = {
  '/csrf': {
    target,
    secure: false,
    changeOrigin: true,
  },
  '/user': {
    target,
    secure: false,
    changeOrigin: true,
  },
  '/admin': {
    target,
    secure: false,
    changeOrigin: true,
    bypass: serveSpaForHtmlNavigation,
  },
  '/payment': {
    target,
    secure: false,
    changeOrigin: true,
  },
  '/ws': {
    target,
    secure: false,
    ws: true,
    changeOrigin: true,
  },
};
