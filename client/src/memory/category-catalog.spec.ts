import {
  CATEGORY_CATALOG,
  CategoryDefinition,
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

  it('provides a non-empty keyword list for every category', () => {
    for (const category of CATEGORY_CATALOG) {
      expect(Array.isArray(category.keywords)).toBe(true);
      expect(category.keywords.length).toBeGreaterThan(0);
      for (const keyword of category.keywords) {
        expect(keyword.trim().length).toBeGreaterThan(0);
      }
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

  it('resolves keyword searches to their category', () => {
    expect(
      filterCategoriesByQuery(CATEGORY_CATALOG, 'ekmek').map((c) => c.value)
    ).toEqual([9]);
    expect(
      filterCategoriesByQuery(CATEGORY_CATALOG, 'peynir').map((c) => c.value)
    ).toEqual([3]);
    expect(
      filterCategoriesByQuery(CATEGORY_CATALOG, 'kedi').map((c) => c.value)
    ).toEqual([17]);
    expect(
      filterCategoriesByQuery(CATEGORY_CATALOG, 'SAMPuan').map(
        (c) => c.value
      )
    ).toEqual([12]);
    expect(
      filterCategoriesByQuery(CATEGORY_CATALOG, 'şampuan').map(
        (c) => c.value
      )
    ).toEqual([12]);
  });

  it('keeps every placeholder example searchable', () => {
    for (const example of ['çiçek', 'dondurma', 'ekmek']) {
      expect(filterCategoriesByQuery(CATEGORY_CATALOG, example).length).toBeGreaterThan(
        0
      );
    }
  });

  it('returns matching categories once and in catalog order', () => {
    const multi = filterCategoriesByQuery(CATEGORY_CATALOG, 'su').map(
      (c) => c.value
    );
    expect(multi).toEqual([6]);
    expect(new Set(multi).size).toBe(multi.length);

    const single = filterCategoriesByQuery(CATEGORY_CATALOG, 'bakim').map(
      (c) => c.value
    );
    expect(single).toEqual([12]);
    expect(new Set(single).size).toBe(single.length);
  });

  it('gives exact normalized name and keyword matches precedence', () => {
    const values = (query: string): number[] =>
      filterCategoriesByQuery(CATEGORY_CATALOG, query).map((c) => c.value);

    expect(values('su')).toEqual([6]);
    expect(values('süt')).toEqual([3]);
    expect(values('sut')).toEqual([3]);
    expect(values('ekmek')).toEqual([9]);
    expect(values('şampuan')).toEqual([12]);
    expect(values('sampuan')).toEqual([12]);
    expect(values('meyv')).toEqual([2]);
  });

  it('returns every exact owner once in catalog order for a shared exact term', () => {
    const localCatalog: CategoryDefinition[] = [
      {
        value: 31,
        name: 'Birinci Yerel',
        image: '/discover-items/petshop.png',
        keywords: ['ortak'],
      },
      {
        value: 32,
        name: 'Ortak İkinci',
        image: '/discover-items/bebek.png',
        keywords: ['başka'],
      },
      {
        value: 33,
        name: 'Üçüncü Yerel',
        image: '/discover-items/cicek.png',
        keywords: ['ortak', 'diger'],
      },
    ];

    const shared = filterCategoriesByQuery(localCatalog, 'ortak').map(
      (c) => c.value
    );
    expect(shared).toEqual([31, 33]);
    expect(new Set(shared).size).toBe(shared.length);

    expect(
      filterCategoriesByQuery(localCatalog, 'ortak ikinci').map((c) => c.value)
    ).toEqual([32]);
  });

  it('returns no category for a query without matches', () => {
    expect(filterCategoriesByQuery(CATEGORY_CATALOG, 'balon patlamasi')).toEqual(
      []
    );
    expect(filterCategoriesByQuery(CATEGORY_CATALOG, 'xyzzy')).toEqual([]);
  });
});
