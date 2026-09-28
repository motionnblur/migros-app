import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../app/config/backend.config';
import { ICheckoutResponse } from '../../interfaces/ICheckoutResponse';
import { ICheckoutStatus } from '../../interfaces/ICheckoutStatus';
import { IPaymentResponse } from '../../interfaces/IPaymentResponse';
import { IPaymentStatus } from '../../interfaces/IPaymentStatus';

@Injectable({ providedIn: 'root' })
export class PaymentApiService {
  constructor(private readonly http: HttpClient) {}

  prepareCheckout(): Observable<ICheckoutResponse> {
    return this.http.post<ICheckoutResponse>(`${API_BASE_URL}/payment/checkouts`, {});
  }

  getCheckoutStatus(checkoutId: string): Observable<ICheckoutStatus> {
    return this.http.get<ICheckoutStatus>(`${API_BASE_URL}/payment/checkouts/${checkoutId}`);
  }

  getPaymentStatus(checkoutId: string): Observable<IPaymentStatus> {
    return this.http.get<IPaymentStatus>(`${API_BASE_URL}/payment/checkouts/${checkoutId}/status`);
  }

  chargeCheckout(checkoutId: string, token: string): Observable<IPaymentResponse> {
    return this.http.post<IPaymentResponse>(`${API_BASE_URL}/payment/checkouts/${checkoutId}/charge`, { token });
  }

  cancelCheckout(checkoutId: string): Observable<ICheckoutStatus> {
    return this.http.post<ICheckoutStatus>(`${API_BASE_URL}/payment/checkouts/${checkoutId}/cancel`, {});
  }
}
