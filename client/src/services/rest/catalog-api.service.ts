import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../app/config/backend.config';
import { IProductData } from '../../interfaces/IProductData';
import { IProductDescription } from '../../interfaces/IProductDescription';
import { IProductPreview } from '../../interfaces/IProductPreview';
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
