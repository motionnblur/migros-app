import { environment } from '../../environments/environment';

const API_PREFIXES = ['/user', '/admin', '/payment'];

export const API_BASE_URL = normalizeBaseUrl(environment.apiBaseUrl);
export const WS_BASE_URL = resolveWsBaseUrl(environment.wsBaseUrl);

export function apiUrl(path: string): string {
  const normalizedPath = normalizePath(path);
  return `${API_BASE_URL}${normalizedPath}`;
}

export function wsUrl(path: string): string {
  const normalizedPath = normalizePath(path);
  return `${WS_BASE_URL}${normalizedPath}`;
}

export function shouldAttachCredentials(url: string): boolean {
  if (!url) {
    return false;
  }

  if (isAbsoluteHttpUrl(url)) {
    if (!API_BASE_URL) {
      return false;
    }
    return url === API_BASE_URL || url.startsWith(`${API_BASE_URL}/`);
  }

  return API_PREFIXES.some((prefix) => url === prefix || url.startsWith(`${prefix}/`));
}

function normalizePath(path: string): string {
  if (!path) {
    return '/';
  }
  return path.startsWith('/') ? path : `/${path}`;
}

function normalizeBaseUrl(value: string): string {
  const normalized = (value || '').trim();
  if (!normalized) {
    return '';
  }
  return normalized.endsWith('/') ? normalized.slice(0, -1) : normalized;
}

function isAbsoluteHttpUrl(url: string): boolean {
  return url.startsWith('http://') || url.startsWith('https://');
}

function resolveWsBaseUrl(value: string): string {
  const configuredBaseUrl = normalizeBaseUrl(value);
  if (configuredBaseUrl) {
    return configuredBaseUrl;
  }

  if (typeof window === 'undefined') {
    return '';
  }

  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
  return `${protocol}://${window.location.host}`;
}
