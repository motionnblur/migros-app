import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { API_BASE_URL } from '../../app/config/backend.config';
import { IUserCartItemDto } from '../../interfaces/IUserCartItemDto';
import { IUserOrderDetail } from '../../interfaces/IUserOrderDetail';
import { IUserOrderGroup } from '../../interfaces/IUserOrderGroup';
import { ICartReconciliation } from '../../interfaces/ICartReconciliation';

@Injectable({ providedIn: 'root' })
export class CartOrdersApiService {
  constructor(private readonly http: HttpClient) {}

  getAllProductsFromUserCart(): Observable<IUserCartItemDto[]> {
    return this.http.get<IUserCartItemDto[]>(`${API_BASE_URL}/user/supply/getProductData`, { responseType: 'json' });
  }

  /**
   * Asks the server to repair the stored cart against what is actually buyable.
   *
   * <p>A cart read is a pure read and deliberately hides entries it cannot
   * render, while checkout reserves from the stored list. Without this, a cart
   * holding an unbuyable product looks complete, then fails at checkout for a
   * line the customer cannot see or remove. It is a POST rather than a GET
   * because it changes stored state, and it reports what it changed so the
   * customer is not silently dropped from their own order.
   */
  reconcileUserCart(): Observable<ICartReconciliation> {
    return this.http.post<ICartReconciliation>(`${API_BASE_URL}/user/supply/reconcileCart`, null, { responseType: 'json' });
  }

  addProductToUserCart(productId: number): Observable<string | null> {
    return this.http.post(`${API_BASE_URL}/user/supply/addProductToUserCart`, null, { params: { productId }, responseType: 'text', observe: 'response' }).pipe(map((response) => response.body));
  }

  removeProductFromUserCart(productId: number): Observable<string | null> {
    return this.http.delete(`${API_BASE_URL}/user/supply/removeProductFromUserCart`, { params: { productId }, responseType: 'text', observe: 'response' }).pipe(map((response) => response.body));
  }

  updateProductCountInUserCart(productId: number, count: number): Observable<string | null> {
    return this.http.post(`${API_BASE_URL}/user/supply/updateProductCountInUserCart`, null, { params: { productId, count }, responseType: 'text', observe: 'response' }).pipe(map((response) => response.body));
  }

  getAllOrderIds(): Observable<number[]> {
    return this.http.get<number[]>(`${API_BASE_URL}/user/supply/getAllOrderIds`, { responseType: 'json' });
  }

  getOrderStatusByOrderId(orderId: number): Observable<string> {
    return this.http.get(`${API_BASE_URL}/user/supply/getOrderStatusByOrderId`, { params: { orderId }, responseType: 'text' });
  }

  calcelOrder(orderId: number): Observable<boolean> {
    return this.http.delete(`${API_BASE_URL}/user/supply/cancelOrder`, { params: { orderId }, responseType: 'text', observe: 'response' }).pipe(map((response) => response.status === 200));
  }

  getUserOrders(): Observable<IUserOrderDetail[]> {
    return this.http.get<IUserOrderDetail[]>(`${API_BASE_URL}/user/supply/getUserOrders`, { responseType: 'json' });
  }

  getUserOrderGroups(): Observable<IUserOrderGroup[]> {
    return this.http.get<IUserOrderGroup[]>(`${API_BASE_URL}/user/supply/getUserOrderGroups`, { responseType: 'json' });
  }
}
