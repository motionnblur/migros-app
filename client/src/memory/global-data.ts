import { CATEGORY_CATALOG, CategoryDefinition } from './category-catalog';

export const data = {
  currentSelectedCategoryId: 0,
  currentSelectedSubCategoryName: '',
  totalCartPrice: 0,
};

export const categories: readonly CategoryDefinition[] = CATEGORY_CATALOG;
