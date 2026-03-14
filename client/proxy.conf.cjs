const target = process.env.NG_PROXY_TARGET || 'http://localhost:8080';

module.exports = {
  '/user': {
    target,
    secure: false,
    changeOrigin: true,
  },
  '/admin': {
    target,
    secure: false,
    changeOrigin: true,
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
