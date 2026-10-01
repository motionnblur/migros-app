/**
 * One selectable subcategory bucket in a catalogue search response.
 *
 * Deliberately its own type rather than `ISubCategory`: that shape carries a
 * `subCategoryId`, which the catalogue has no table to produce - the existing
 * endpoint fills it with the *category's* id - and the search response reports
 * no id at all. Reusing the old shape would invite a caller to filter by a
 * field that identifies the category instead of the subcategory.
 */
export interface ISubCategoryCount {
  subCategoryName: string;
  productCount: number;
}