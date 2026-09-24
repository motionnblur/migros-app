import { staticImageUrl, supabaseImageUrl } from './supabase-assets';

describe('staticImageUrl', () => {
  it('uses root-relative local assets outside production', () => {
    expect(staticImageUrl('/discover-items/atistirma.png', false)).toBe(
      '/discover-items/atistirma.png'
    );
  });

  it('maps legacy discover names to their local filenames', () => {
    expect(staticImageUrl('discover-items/stkvlt.png', false)).toBe(
      '/discover-items/süt-kahvaltilik.png'
    );
    expect(staticImageUrl('discover-items/cicek.png', false)).toBe(
      '/discover-items/çiçek.png'
    );
  });

  it('uses Supabase in production', () => {
    expect(staticImageUrl('discover-items/stkvlt.png', true)).toBe(
      supabaseImageUrl('discover-items/stkvlt.png')
    );
  });

  it('passes absolute URLs through unchanged', () => {
    const url = 'https://example.test/image.png';
    expect(staticImageUrl(url, false)).toBe(url);
    expect(staticImageUrl(url, true)).toBe(url);
  });
});
