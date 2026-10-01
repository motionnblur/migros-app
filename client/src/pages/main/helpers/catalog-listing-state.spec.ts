import { convertToParamMap } from '@angular/router';

import {
  LISTING_AVAILABILITY_OPTIONS,
  LISTING_SORT_OPTIONS,
  buildListingFilterChips,
  buildListingQueryParams,
  defaultListingFilters,
  hasActiveListingFilters,
  hasSameListingQueryParams,
  listingFiltersFromParams,
  listingRequestSignature,
  normalizeListingAvailability,
  normalizeListingSort,
  normalizePriceBounds,
  normalizePriceText,
  parsePriceBound,
  pickListingQueryParams,
  priceRangeError,
  resolveListingSubCategoryName,
  toProductSearchQuery,
} from './catalog-listing-state';
import { ListingFilterState } from './catalog-listing-state';
import { ISubCategoryCount } from '../../../interfaces/ISubCategoryCount';

function filters(overrides: Partial<ListingFilterState> = {}): ListingFilterState {
  return { ...defaultListingFilters(), ...overrides };
}

describe('catalog-listing-state', () => {
  describe('normalizeListingAvailability', () => {
    it('accepts the endpoint values whatever their casing in a URL', () => {
      expect(normalizeListingAvailability('IN_STOCK')).toBe('IN_STOCK');
      expect(normalizeListingAvailability('in_stock')).toBe('IN_STOCK');
      expect(normalizeListingAvailability(' out_of_stock ')).toBe('OUT_OF_STOCK');
    });

    it('falls back to ALL, which is the endpoint default', () => {
      expect(normalizeListingAvailability('ALL')).toBe('ALL');
      expect(normalizeListingAvailability('')).toBe('ALL');
      expect(normalizeListingAvailability(null)).toBe('ALL');
      expect(normalizeListingAvailability('in-stock')).toBe('ALL');
    });
  });

  describe('normalizeListingSort', () => {
    it('reads the price orderings leniently', () => {
      expect(normalizeListingSort('PRICE_ASC')).toBe('PRICE_ASC');
      expect(normalizeListingSort('price_desc')).toBe('PRICE_DESC');
    });

    it('falls back to DEFAULT for anything else', () => {
      expect(normalizeListingSort('DEFAULT')).toBe('DEFAULT');
      expect(normalizeListingSort('price')).toBe('DEFAULT');
      expect(normalizeListingSort(undefined)).toBe('DEFAULT');
    });
  });

  describe('normalizePriceText', () => {
    it('accepts the Turkish decimal comma', () => {
      expect(normalizePriceText('12,50')).toBe('12.50');
      expect(normalizePriceText(' 12 ')).toBe('12');
    });

    it('treats an empty field as no bound', () => {
      expect(normalizePriceText('')).toBe('');
      expect(normalizePriceText('   ')).toBe('');
      expect(normalizePriceText(null)).toBe('');
    });

    it('rejects what the endpoint would answer with a 400', () => {
      // Negative, three decimals, thousands separators and free text all fail.
      expect(normalizePriceText('-5')).toBe('');
      expect(normalizePriceText('1.234')).toBe('');
      expect(normalizePriceText('1.234,5')).toBe('');
      expect(normalizePriceText('abc')).toBe('');
      expect(normalizePriceText('10 TL')).toBe('');
      expect(normalizePriceText('1e2')).toBe('');
    });

    it('parses the normalized text into a number', () => {
      expect(parsePriceBound('12,50')).toBe(12.5);
      expect(parsePriceBound('0')).toBe(0);
      expect(parsePriceBound('')).toBeUndefined();
    });
  });

  describe('priceRangeError', () => {
    it('accepts a consistent pair and either bound alone', () => {
      expect(priceRangeError('10', '50')).toBeNull();
      expect(priceRangeError('10', '')).toBeNull();
      expect(priceRangeError('', '50')).toBeNull();
      expect(priceRangeError('50', '50')).toBeNull();
      expect(priceRangeError('abc', '50')).toBeNull();
    });

    it('names the contradiction instead of reordering the customer input', () => {
      expect(priceRangeError('50', '10')).toBe(
        'En düşük fiyat, en yüksek fiyattan büyük olamaz.',
      );
    });
  });

  describe('normalizePriceBounds', () => {
    it('drops an inverted maximum so a shared link still loads', () => {
      expect(normalizePriceBounds('50', '10')).toEqual({
        minPrice: '50',
        maxPrice: '',
      });
      expect(normalizePriceBounds('10', '50')).toEqual({
        minPrice: '10',
        maxPrice: '50',
      });
    });
  });

  describe('listingFiltersFromParams', () => {
    it('reads every control out of the URL', () => {
      expect(
        listingFiltersFromParams(
          convertToParamMap({
            availability: 'in_stock',
            minPrice: '10',
            maxPrice: '50',
            discounted: 'true',
            sort: 'price_asc',
          }),
        ),
      ).toEqual({
        availability: 'IN_STOCK',
        minPrice: '10',
        maxPrice: '50',
        discountedOnly: true,
        sort: 'PRICE_ASC',
      });
    });

    it('reports the defaults for an empty URL', () => {
      expect(listingFiltersFromParams(convertToParamMap({}))).toEqual(
        defaultListingFilters(),
      );
      expect(listingFiltersFromParams(null)).toEqual(defaultListingFilters());
    });

    it('treats a discounted value that is not exactly true as off', () => {
      expect(
        listingFiltersFromParams(convertToParamMap({ discounted: '1' })).discountedOnly,
      ).toBeFalse();
      expect(
        listingFiltersFromParams(convertToParamMap({ discounted: 'TRUE' }))
          .discountedOnly,
      ).toBeFalse();
    });
  });

  describe('hasActiveListingFilters', () => {
    it('is false for the default state', () => {
      expect(hasActiveListingFilters(defaultListingFilters())).toBeFalse();
      expect(hasActiveListingFilters(null)).toBeFalse();
    });

    it('is true for each control that narrows the result', () => {
      expect(hasActiveListingFilters(filters({ availability: 'IN_STOCK' }))).toBeTrue();
      expect(hasActiveListingFilters(filters({ minPrice: '10' }))).toBeTrue();
      expect(hasActiveListingFilters(filters({ maxPrice: '10' }))).toBeTrue();
      expect(hasActiveListingFilters(filters({ discountedOnly: true }))).toBeTrue();
      expect(hasActiveListingFilters(filters({ sort: 'PRICE_DESC' }))).toBeTrue();
    });
  });

  describe('buildListingQueryParams', () => {
    it('omits every default so a plain listing URL stays clean', () => {
      expect(
        buildListingQueryParams({
          q: '',
          subCategoryName: '',
          page: 1,
          filters: defaultListingFilters(),
        }),
      ).toEqual({});
      expect(buildListingQueryParams({})).toEqual({});
    });

    it('keeps the term, the subcategory and the page', () => {
      expect(
        buildListingQueryParams({ q: 'çiçek', subCategoryName: 'Süt', page: 3 }),
      ).toEqual({ q: 'çiçek', subcategory: 'Süt', page: '3' });
    });

    it('writes each non-default control', () => {
      expect(
        buildListingQueryParams({
          filters: filters({
            availability: 'OUT_OF_STOCK',
            minPrice: '10,5',
            maxPrice: '50',
            discountedOnly: true,
            sort: 'PRICE_DESC',
          }),
        }),
      ).toEqual({
        availability: 'OUT_OF_STOCK',
        minPrice: '10.5',
        maxPrice: '50',
        discounted: 'true',
        sort: 'PRICE_DESC',
      });
    });

    it('normalizes the bounds it writes', () => {
      expect(
        buildListingQueryParams({
          filters: filters({ minPrice: '10,00', maxPrice: '' }),
        }),
      ).toEqual({ minPrice: '10.00' });
    });

    it('does not write an inverted pair', () => {
      expect(
        buildListingQueryParams({
          filters: filters({ minPrice: '90', maxPrice: '10' }),
        }),
      ).toEqual({ minPrice: '90' });
    });
  });

  describe('pickListingQueryParams', () => {
    it('keeps only the listing parameters that carry a value', () => {
      expect(
        pickListingQueryParams({
          q: 'çiçek',
          subcategory: 'Süt',
          page: '2',
          availability: 'IN_STOCK',
          minPrice: '10',
          maxPrice: '',
          discounted: 'true',
          sort: 'PRICE_ASC',
          unrelated: 'x',
          tracking: '',
        }),
      ).toEqual({
        q: 'çiçek',
        subcategory: 'Süt',
        page: '2',
        availability: 'IN_STOCK',
        minPrice: '10',
        discounted: 'true',
        sort: 'PRICE_ASC',
      });
    });

    it('reads a ParamMap as well as a plain object', () => {
      expect(
        pickListingQueryParams(
          convertToParamMap({ q: 'süt', page: '4', ignored: 'y' }),
        ),
      ).toEqual({ q: 'süt', page: '4' });
      expect(pickListingQueryParams(null)).toEqual({});
    });
  });

  describe('hasSameListingQueryParams', () => {
    it('matches only when the URL already spells the listing', () => {
      expect(hasSameListingQueryParams({}, {})).toBeTrue();
      expect(
        hasSameListingQueryParams({ availability: 'IN_STOCK' }, { availability: 'IN_STOCK' }),
      ).toBeTrue();

      expect(hasSameListingQueryParams({ availability: 'in_stock' }, { availability: 'IN_STOCK' })).toBeFalse();
      expect(hasSameListingQueryParams({ availability: 'IN_STOCK' }, {})).toBeFalse();
      expect(hasSameListingQueryParams({ page: '2' }, { availability: 'IN_STOCK' })).toBeFalse();
      expect(hasSameListingQueryParams({ unrelated: '1' }, {})).toBeTrue();
    });
  });

  describe('toProductSearchQuery', () => {
    it('omits the defaults the endpoint already means', () => {
      expect(
        toProductSearchQuery({
          q: '  ',
          page: 1,
          size: 10,
          filters: defaultListingFilters(),
        }),
      ).toEqual({ page: 0, size: 10 });
    });

    it('translates the one-based UI page into the endpoint page index', () => {
      expect(toProductSearchQuery({ page: 4, size: 10 }).page).toBe(3);
      expect(toProductSearchQuery({ page: null, size: 10 }).page).toBe(0);
    });

    it('forwards every non-default control in the endpoint casing', () => {
      expect(
        toProductSearchQuery({
          q: 'çiçek',
          categoryId: 16,
          subCategoryName: 'Süt',
          page: 1,
          size: 10,
          filters: filters({
            availability: 'OUT_OF_STOCK',
            minPrice: '10,50',
            maxPrice: '50',
            discountedOnly: true,
            sort: 'PRICE_ASC',
          }),
        }),
      ).toEqual({
        q: 'çiçek',
        categoryId: 16,
        subcategory: 'Süt',
        availability: 'OUT_OF_STOCK',
        minPrice: 10.5,
        maxPrice: 50,
        discountedOnly: true,
        sort: 'PRICE_ASC',
        page: 0,
        size: 10,
      });
    });

    it('never sends a subcategory without the category it is scoped to', () => {
      // The endpoint answers 400 for a subcategory on its own, so a search
      // listing must never produce this request.
      expect(
        toProductSearchQuery({
          subCategoryName: 'Süt',
          page: 1,
          size: 10,
        }).subcategory,
      ).toBeUndefined();
      expect(
        toProductSearchQuery({ categoryId: 0, subCategoryName: 'Süt', size: 10 })
          .categoryId,
      ).toBeUndefined();
    });

    it('drops an inverted price pair rather than asking for a 400', () => {
      expect(
        toProductSearchQuery({
          size: 10,
          filters: filters({ minPrice: '90', maxPrice: '10' }),
        }),
      ).toEqual({ minPrice: 90, page: 0, size: 10 });
    });
  });

  describe('listingRequestSignature', () => {
    it('is equal for the same listing and different for another', () => {
      const base = { q: 'süt', categoryId: 3, page: 1, size: 10 };

      expect(listingRequestSignature(base)).toBe(
        listingRequestSignature({ ...base, page: 1 }),
      );
      expect(listingRequestSignature(base)).not.toBe(
        listingRequestSignature({ ...base, page: 2 }),
      );
      expect(listingRequestSignature(base)).not.toBe(
        listingRequestSignature({ ...base, categoryId: 6 }),
      );
    });
  });

  describe('buildListingFilterChips', () => {
    it('renders nothing while every control is at its default', () => {
      expect(buildListingFilterChips(defaultListingFilters())).toEqual([]);
      expect(buildListingFilterChips(null)).toEqual([]);
    });

    it('renders one chip per applied control, in toolbar order', () => {
      expect(
        buildListingFilterChips(
          filters({
            availability: 'IN_STOCK',
            minPrice: '10',
            maxPrice: '50',
            discountedOnly: true,
            sort: 'PRICE_DESC',
          }),
        ),
      ).toEqual([
        { key: 'availability', label: 'Bulunabilirlik: Stokta' },
        { key: 'price', label: 'Fiyat: 10 TL – 50 TL' },
        { key: 'discounted', label: 'Yalnızca indirimli ürünler' },
        { key: 'sort', label: 'Fiyat: yüksekten düşüğe' },
      ]);
    });

    it('describes a single bound in Turkish', () => {
      expect(buildListingFilterChips(filters({ minPrice: '12,5' }))).toEqual([
        { key: 'price', label: 'Fiyat: 12,5 TL ve üzeri' },
      ]);
      expect(buildListingFilterChips(filters({ maxPrice: '99,99' }))).toEqual([
        { key: 'price', label: 'Fiyat: 99,99 TL ve altı' },
      ]);
    });

    it('reports the sold-out filter distinctly from in stock', () => {
      expect(buildListingFilterChips(filters({ availability: 'OUT_OF_STOCK' }))).toEqual([
        { key: 'availability', label: 'Bulunabilirlik: Tükendi' },
      ]);
    });
  });

  describe('subcategory buckets', () => {
    const buckets: ISubCategoryCount[] = [
      { subCategoryName: 'Süt', productCount: 12 },
      { subCategoryName: 'Peynir', productCount: 8 },
    ];

    it('keeps a name the server offered and collapses the rest', () => {
      expect(resolveListingSubCategoryName(buckets, 'Peynir')).toBe('Peynir');
      expect(resolveListingSubCategoryName(buckets, ' Peynir ')).toBe('Peynir');
      expect(resolveListingSubCategoryName(buckets, 'Olmayan')).toBe('');
      expect(resolveListingSubCategoryName(buckets, '')).toBe('');
    });

    /**
     * An empty bucket list means the category holds nothing for the active
     * filters, not that the name was wrong. Repairing it there would drop the
     * customer's selection and cost a pointless round trip.
     */
    it('keeps a requested name when the server offered no buckets at all', () => {
      expect(resolveListingSubCategoryName([], 'Süt')).toBe('Süt');
      expect(resolveListingSubCategoryName(null, 'Süt')).toBe('Süt');
    });
  });

  it('offers the availability and sort choices the endpoint understands', () => {
    expect(LISTING_AVAILABILITY_OPTIONS.map((option) => option.value)).toEqual([
      'ALL',
      'IN_STOCK',
      'OUT_OF_STOCK',
    ]);
    expect(LISTING_SORT_OPTIONS.map((option) => option.value)).toEqual([
      'DEFAULT',
      'PRICE_ASC',
      'PRICE_DESC',
    ]);
  });
});