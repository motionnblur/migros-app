import {
  PRODUCT_PAGE_SIZE,
  clampPageToRange,
  pageCountForProductCount,
  parsePageParam,
} from './category-browse-state';

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
});