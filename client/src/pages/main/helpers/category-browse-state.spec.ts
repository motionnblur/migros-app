import {
  PRODUCT_PAGE_SIZE,
  buildBrowseQueryParams,
  clampPageToRange,
  countForSelection,
  hasSameBrowseQueryParams,
  pageCountForProductCount,
  parsePageParam,
  resolveSubCategoryName,
} from './category-browse-state';
import { ISubCategory } from '../../../interfaces/ISubCategory';

function subCategory(name: string, productCount: number): ISubCategory {
  return { subCategoryId: 1, subCategoryName: name, productCount };
}

describe('category-browse-state', () => {
  describe('parsePageParam', () => {
    it('reads positive whole numbers', () => {
      expect(parsePageParam('1')).toBe(1);
      expect(parsePageParam('7')).toBe(7);
      expect(parsePageParam(' 3 ')).toBe(3);
    });

    it('rejects values that are not positive whole numbers', () => {
      expect(parsePageParam(null)).toBeNull();
      expect(parsePageParam(undefined)).toBeNull();
      expect(parsePageParam('')).toBeNull();
      expect(parsePageParam('0')).toBeNull();
      expect(parsePageParam('-2')).toBeNull();
      expect(parsePageParam('1.5')).toBeNull();
      expect(parsePageParam('abc')).toBeNull();
      expect(parsePageParam('1e3')).toBeNull();
      expect(parsePageParam('99999999999999999999')).toBeNull();
    });
  });

  describe('resolveSubCategoryName', () => {
    const subCategories = [subCategory('Süt', 4), subCategory('Peynir', 2)];

    it('keeps a known subcategory', () => {
      expect(resolveSubCategoryName(subCategories, 'Peynir')).toBe('Peynir');
      expect(resolveSubCategoryName(subCategories, ' Peynir ')).toBe('Peynir');
    });

    it('collapses unknown or empty values to the default selection', () => {
      expect(resolveSubCategoryName(subCategories, 'Bilinmeyen')).toBe('');
      expect(resolveSubCategoryName(subCategories, '')).toBe('');
      expect(resolveSubCategoryName(subCategories, null)).toBe('');
      expect(resolveSubCategoryName(null, 'Süt')).toBe('');
    });
  });

  describe('countForSelection', () => {
    const subCategories = [subCategory('Süt', 4), subCategory('Peynir', 2)];

    it('uses the category count for the default selection', () => {
      expect(countForSelection(subCategories, 42, '')).toBe(42);
    });

    it('uses the subcategory count for a selected subcategory', () => {
      expect(countForSelection(subCategories, 42, 'Peynir')).toBe(2);
    });

    it('returns zero for an unknown subcategory', () => {
      expect(countForSelection(subCategories, 42, 'Yok')).toBe(0);
    });
  });

  describe('pageCountForProductCount', () => {
    it('derives the page count from the existing page size', () => {
      expect(PRODUCT_PAGE_SIZE).toBe(10);
      expect(pageCountForProductCount(0)).toBe(1);
      expect(pageCountForProductCount(1)).toBe(1);
      expect(pageCountForProductCount(10)).toBe(1);
      expect(pageCountForProductCount(11)).toBe(2);
      expect(pageCountForProductCount(35)).toBe(4);
    });

    it('never drops below a single page', () => {
      expect(pageCountForProductCount(-5)).toBe(1);
      expect(pageCountForProductCount(Number.NaN)).toBe(1);
    });
  });

  describe('clampPageToRange', () => {
    it('keeps a page inside the available range', () => {
      expect(clampPageToRange(2, 5)).toBe(2);
      expect(clampPageToRange(9, 5)).toBe(5);
      expect(clampPageToRange(0, 5)).toBe(1);
      expect(clampPageToRange(null, 5)).toBe(1);
      expect(clampPageToRange(3, 0)).toBe(1);
    });
  });

  describe('buildBrowseQueryParams', () => {
    it('omits both parameters for the default selection and first page', () => {
      expect(buildBrowseQueryParams('', 1)).toEqual({});
      expect(buildBrowseQueryParams(null, null)).toEqual({});
    });

    it('keeps only the subcategory on the first page', () => {
      expect(buildBrowseQueryParams('Peynir', 1)).toEqual({ subcategory: 'Peynir' });
    });

    it('keeps the page parameter for later pages', () => {
      expect(buildBrowseQueryParams('', 4)).toEqual({ page: '4' });
      expect(buildBrowseQueryParams('Peynir', 4)).toEqual({
        subcategory: 'Peynir',
        page: '4',
      });
    });
  });

  describe('hasSameBrowseQueryParams', () => {
    it('matches only when the current URL already matches', () => {
      expect(hasSameBrowseQueryParams({}, {})).toBeTrue();
      expect(hasSameBrowseQueryParams({ subcategory: 'Peynir' }, { subcategory: 'Peynir' })).toBeTrue();
      expect(hasSameBrowseQueryParams({ subcategory: 'Peynir', page: '2' }, { subcategory: 'Peynir', page: '2' })).toBeTrue();

      expect(hasSameBrowseQueryParams({ subcategory: 'Peynir' }, {})).toBeFalse();
      expect(hasSameBrowseQueryParams({ subcategory: 'Bilinmeyen' }, { subcategory: 'Peynir' })).toBeFalse();
      expect(hasSameBrowseQueryParams({ subcategory: 'Peynir', page: '2' }, { subcategory: 'Peynir' })).toBeFalse();
    });
  });
});
