const DEFAULT_SUPABASE_IMAGE_BASE_URL =
  'https://mskiasfajbnrpwkqdbwn.supabase.co/storage/v1/object/public/migros-app-images/public/';

type RuntimeConfig = {
  SUPABASE_IMAGE_BASE_URL?: string;
};

function normalizeBaseUrl(value: string): string {
  const trimmed = (value || '').trim();
  if (!trimmed) {
    return DEFAULT_SUPABASE_IMAGE_BASE_URL;
  }

  return trimmed.endsWith('/') ? trimmed : `${trimmed}/`;
}

function getRuntimeBaseUrl(): string {
  const runtimeConfig =
    (globalThis as { __env?: RuntimeConfig }).__env?.SUPABASE_IMAGE_BASE_URL ??
    '';

  return normalizeBaseUrl(runtimeConfig || DEFAULT_SUPABASE_IMAGE_BASE_URL);
}

export const SUPABASE_IMAGE_BASE_URL = getRuntimeBaseUrl();

export function supabaseImageUrl(path: string): string {
  const normalizedPath = (path || '').trim();
  if (!normalizedPath) {
    return SUPABASE_IMAGE_BASE_URL;
  }

  if (/^https?:\/\//i.test(normalizedPath)) {
    return normalizedPath;
  }

  const cleanedPath = normalizedPath.replace(/^\/+/, '');
  return `${SUPABASE_IMAGE_BASE_URL}${cleanedPath}`;
}
