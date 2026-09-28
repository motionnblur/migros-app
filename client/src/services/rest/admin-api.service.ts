import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { API_BASE_URL } from '../../app/config/backend.config';
import { IAdminProductPreview } from '../../interfaces/IAdminProductPreview';
import { IOrderPage } from '../../interfaces/IOrderPage';
import { IProductDescription } from '../../interfaces/IProductDescription';
import { IProductUpdater } from '../../interfaces/IProductUpdater';
import { IProductUploader } from '../../interfaces/IProductUploader';
import { IUserProfileTable } from '../../interfaces/IUserProfileTable';

@Injectable({ providedIn: 'root' })
export class AdminApiService {
  constructor(private readonly http: HttpClient) {}

  getProductPageDataAdmin(categoryId: number, page: number, productRange: number): Observable<Object> {
    return this.http.get(`${API_BASE_URL}/admin/supply/getProductsFromCategory`, { params: { categoryId, page, productRange }, responseType: 'json' });
  }

  getProductCountsFromCategoryAdmin(categoryId: number): Observable<Object> {
    return this.http.get(`${API_BASE_URL}/admin/supply/getProductCountsFromCategory`, { params: { categoryId }, responseType: 'json' });
  }

  getAllProductsAdmin(page: number, productRange: number): Observable<Object> {
    return this.http.get(`${API_BASE_URL}/admin/supply/getAllProducts`, { params: { page, productRange }, responseType: 'json' });
  }

  getAllProductCountsAdmin(): Observable<Object> {
    return this.http.get(`${API_BASE_URL}/admin/supply/getAllProductCounts`, { responseType: 'json' });
  }

  getAllAdminProducts(adminId: number, page: number, productRange: number): Observable<IAdminProductPreview[]> {
    return this.http.get<IAdminProductPreview[]>(`${API_BASE_URL}/admin/panel/getAllAdminProducts`, { params: { adminId, page, productRange }, responseType: 'json' });
  }

  uploadProductData(productData: IProductUploader): Observable<boolean> {
    const formData = new FormData();
    formData.append('adminId', productData.adminId.toString());
    formData.append('productName', this.normalizeStringField(productData.productName));
    formData.append('subCategoryName', this.normalizeStringField(productData.subCategoryName));
    formData.append('productPrice', productData.productPrice.toString());
    formData.append('productCount', productData.productCount.toString());
    formData.append('productDiscount', productData.productDiscount.toString());
    formData.append('productDescription', this.normalizeOptionalStringField(productData.productDescription));
    if (productData.selectedImage) formData.append('selectedImage', productData.selectedImage);
    formData.append('categoryValue', productData.categoryValue.toString());
    return this.http.post(`${API_BASE_URL}/admin/panel/uploadProduct`, formData, { responseType: 'text', observe: 'response' }).pipe(map((response) => response.status === 200));
  }

  updateProductData(productData: IProductUpdater): Observable<boolean> {
    const formData = new FormData();
    formData.append('adminId', productData.adminId.toString());
    formData.append('productId', productData.productId.toString());
    formData.append('productName', this.normalizeStringField(productData.productName));
    formData.append('subCategoryName', this.normalizeStringField(productData.subCategoryName));
    formData.append('productPrice', productData.productPrice.toString());
    formData.append('productCount', productData.productCount.toString());
    formData.append('productDiscount', productData.productDiscount.toString());
    formData.append('productDescription', this.normalizeOptionalStringField(productData.productDescription));
    if (productData.selectedImage) formData.append('selectedImage', productData.selectedImage);
    formData.append('categoryValue', productData.categoryValue.toString());
    return this.http.post(`${API_BASE_URL}/admin/panel/updateProduct`, formData, { responseType: 'text', observe: 'response' }).pipe(map((response) => response.status === 200));
  }

  updateOrderStatus(orderId: number, status: string): Observable<boolean> {
    return this.http.post(`${API_BASE_URL}/admin/panel/updateOrderStatus`, null, { params: { orderId, status }, responseType: 'text', observe: 'response' }).pipe(map((response) => response.status === 200));
  }

  deleteProduct(productId: number): Observable<boolean> {
    return this.http.delete(`${API_BASE_URL}/admin/panel/deleteProduct`, { params: { productId }, responseType: 'text', observe: 'response' }).pipe(map((response) => response.status === 200));
  }

  addProductDescription(productDescription: IProductDescription): Observable<boolean> {
    const data: IProductDescription = { productId: productDescription.productId, descriptionList: productDescription.descriptionList };
    return this.http.post(`${API_BASE_URL}/admin/panel/addProductDescription`, data, { responseType: 'text', observe: 'response' }).pipe(map((response) => response.status === 200));
  }

  deleteProductDescription(descriptionId: number): Observable<boolean> {
    return this.http.delete(`${API_BASE_URL}/admin/panel/deleteProductDescription`, { params: { descriptionId }, responseType: 'text', observe: 'response' }).pipe(map((response) => response.status === 200));
  }

  getAllOrders(page: number, productRange: number): Observable<IOrderPage> {
    return this.http.get<IOrderPage>(`${API_BASE_URL}/admin/panel/getAllOrders`, { params: { page, productRange }, responseType: 'json' });
  }

  getUserProfileData(orderId: number): Observable<IUserProfileTable> {
    return this.http.get<IUserProfileTable>(`${API_BASE_URL}/admin/panel/getUserProfileData`, { params: { orderId }, responseType: 'json' });
  }

  deleteOrder(orderId: number): Observable<boolean> {
    return this.http.delete(`${API_BASE_URL}/admin/panel/deleteOrder`, { params: { orderId }, responseType: 'text', observe: 'response' }).pipe(map((response) => response.status === 200));
  }

  private normalizeStringField(value: string): string { return (value ?? '').trim(); }

  private normalizeOptionalStringField(value: string): string {
    const normalized = (value ?? '').trim();
    return normalized.toLowerCase() === 'undefined' || normalized.toLowerCase() === 'null' ? '' : normalized;
  }
}
