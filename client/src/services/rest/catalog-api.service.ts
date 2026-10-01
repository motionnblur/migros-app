import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../app/config/backend.config';
import { IProductData } from '../../interfaces/IProductData';
import { IProductDescription } from '../../interfaces/IProductDescription';
import { IProductPreview } from '../../interfaces/IProductPreview';
import { IProductSearchQuery } from '../../interfaces/IProductSearchQuery';
import { IProductSearchResponse } from '../../interfaces/IProductSearchResponse';
import { ISubCategory } from '../../interfaces/ISubCategory';

@Injectable({ providedIn: 'root' })
export class CatalogApiService {
  constructor(private readonly http: HttpClient) {}

  getProductPageData(categoryId: number, page: number, productRange: number): Observable<IProductPreview[]> {
    return this.http.get<IProductPreview[]>(`${API_BASE_URL}/user/supply/getProductsFromCategory`, { params: { categoryId, page, productRange }, responseType: 'json' });
  }

  getProductCountsFromCategory(categoryId: number): Observable<number> {
    return this.http.get<number>(`${API_BASE_URL}/user/supply/getProductCountsFromCategory`, { params: { categoryId }, responseType: 'json' });
  }

  getProductCountsFromSubCategory(subcategoryName: string): Observable<number> {
    return this.http.get<number>(`${API_BASE_URL}/user/supply/getProductCountsFromSubcategory`, { params: { subcategoryName }, responseType: 'json' });
  }

  /**
   * The one filtered, sorted, paged catalogue query, shared by the category
   * listing and the search results listing.
   *
   * Absent values are omitted instead of being sent as `null` or an empty
   * string, because the endpoint's defaults are the ones a listing means: no
   * availability filter (`ALL`, which keeps sold-out products) and `DEFAULT`
   * sort (in stock first, then product id ascending). Sending them explicitly
   * would put the same assumption in the URL and the request.
   *
   * A `subcategory` is only forwarded together with a `categoryId`. The endpoint
   * answers 400 for a subcategory on its own - the name is not unique across the
   * catalogue - so dropping it here is the difference between a filter and an
   * error.
   */
  searchProducts(query: IProductSearchQuery): Observable<IProductSearchResponse> {
    return this.http.get<IProductSearchResponse>(
      `${API_BASE_URL}/user/supply/searchProducts`,
      { params: toSearchParams(query), responseType: 'json' },
    );
  }

  getProductImage(productId: number): Observable<Blob> {
    return this.http.get(`${API_BASE_URL}/user/supply/getProductImage`, { params: { productId }, responseType: 'blob' });
  }

  getProductData(productId: number): Observable<IProductData> {
    return this.http.get<IProductData>(`${API_BASE_URL}/user/supply/getProductDataWithProductId`, { params: { productId }, responseType: 'json' });
  }

  getProductDataForUserCart(productId: number): Observable<IProductData> {
    return this.http.get<IProductData>(`${API_BASE_URL}/user/supply/getProductData`, { params: { productId }, responseType: 'json' });
  }

  getProductDescription(productId: number): Observable<IProductDescription> {
    return this.http.get<IProductDescription>(`${API_BASE_URL}/user/supply/getProductDescription`, { params: { productId }, responseType: 'json' });
  }

  getSubCategories(categoryId: number): Observable<ISubCategory[]> {
    return this.http.get<ISubCategory[]>(`${API_BASE_URL}/user/supply/getSubCategories`, { params: { categoryId }, responseType: 'json' });
  }

  getProducstFromSubCategory(subcategoryName: string, page: number, productRange: number): Observable<IProductPreview[]> {
    return this.http.get<IProductPreview[]>(`${API_BASE_URL}/user/supply/getProductsFromSubcategory`, { params: { subcategoryName, page, productRange }, responseType: 'json' });
  }
}

function toSearchParams(query: IProductSearchQuery): HttpParams {
  let params = new HttpParams();

  const append = (name: string, value: string | number | boolean): void => {
    params = params.append(name, String(value));
  };

  const term = (query.q ?? '').trim();
  if (term) {
    append('q', term);
  }

  if (query.categoryId !== undefined && query.categoryId !== null) {
    append('categoryId', query.categoryId);

    const subcategory = (query.subcategory ?? '').trim();
    if (subcategory) {
      append('subcategory', subcategory);
    }
  }

  if (query.availability !== undefined && query.availability !== null) {
    append('availability', query.availability);
  }

  if (query.minPrice !== undefined && query.minPrice !== null) {
    append('minPrice', query.minPrice);
  }

  if (query.maxPrice !== undefined && query.maxPrice !== null) {
    append('maxPrice', query.maxPrice);
  }

  if (query.discountedOnly !== undefined && query.discountedOnly !== null) {
    append('discountedOnly', query.discountedOnly);
  }

  if (query.sort !== undefined && query.sort !== null) {
    append('sort', query.sort);
  }

  if (query.page !== undefined && query.page !== null) {
    append('page', query.page);
  }

  if (query.size !== undefined && query.size !== null) {
    append('size', query.size);
  }

  return params;
}