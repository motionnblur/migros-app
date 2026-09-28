import {
  CATEGORY_CATALOG,
  filterCategoriesByQuery,
  normalizeCategorySearch,
} from './category-catalog';

describe('category catalog', () => {
  it('exposes 18 categories with unique ids and an image path', () => {
    expect(CATEGORY_CATALOG.length).toBe(18);

    const ids = CATEGORY_CATALOG.map((category) => category.value);
    expect(ids).toEqual([...ids].sort((a, b) => a - b));
    expect(new Set(ids).size).toBe(18);

    for (const category of CATEGORY_CATALOG) {
      expect(category.name.length).toBeGreaterThan(0);
      expect(category.image.startsWith('/discover-items/')).toBe(true);
    }
  });

  it('keeps corrected visible labels without changing ids', () => {
    expect(CATEGORY_CATALOG.find((c) => c.value === 8)?.name).toBe(
      'Atıştırmalık'
    );
    expect(CATEGORY_CATALOG.find((c) => c.value === 16)?.name).toBe('Çiçek');
    expect(CATEGORY_CATALOG.find((c) => c.value === 12)?.name).toBe(
      'Kişisel Bakım, Kozmetik, Sağlık'
    );
  });

  it('normalizes case, punctuation and Turkish characters', () => {
    expect(normalizeCategorySearch('  Çiçek  ')).toBe('cicek');
    expect(normalizeCategorySearch('ATIŞTIRMALIK')).toBe('atistirmalik');
    expect(normalizeCategorySearch('Meyve, Sebze')).toBe('meyve sebze');
    expect(normalizeCategorySearch('SÜT, KAHVALTILIK')).toBe(
      'sut kahvaltilik'
    );
  });

  it('returns the whole catalog for an empty query', () => {
    expect(filterCategoriesByQuery(CATEGORY_CATALOG, '')).toEqual([
      ...CATEGORY_CATALOG,
    ]);
    expect(filterCategoriesByQuery(CATEGORY_CATALOG, '   ')).toEqual([
      ...CATEGORY_CATALOG,
    ]);
  });

  it('matches case-insensitively and Turkish-tolerantly', () => {
    expect(
      filterCategoriesByQuery(CATEGORY_CATALOG, 'cicek').map((c) => c.value)
    ).toEqual([16]);
    expect(
      filterCategoriesByQuery(CATEGORY_CATALOG, 'CICEK').map((c) => c.value)
    ).toEqual([16]);
    expect(
      filterCategoriesByQuery(CATEGORY_CATALOG, 'atistirmalik').map(
        (c) => c.value
      )
    ).toEqual([8]);
    expect(
      filterCategoriesByQuery(CATEGORY_CATALOG, 'MEYVE SEBZE').map(
        (c) => c.value
      )
    ).toEqual([2]);
    expect(
      filterCategoriesByQuery(CATEGORY_CATALOG, 'sut kahvaltilik').map(
        (c) => c.value
      )
    ).toEqual([3]);
  });

  it('returns no category for a query without matches', () => {
    expect(filterCategoriesByQuery(CATEGORY_CATALOG, 'balon patlamasi')).toEqual(
      []
    );
  });
});
