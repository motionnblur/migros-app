import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { API_BASE_URL } from '../../app/config/backend.config';
import { IChatMessage } from '../../interfaces/IChatMessage';
import { ISupportCustomerSummary } from '../../interfaces/support/ISupportCustomerSummary';

@Injectable({ providedIn: 'root' })
export class SupportApiService {
  constructor(private readonly http: HttpClient) {}

  getSupportMessages(): Observable<IChatMessage[]> {
    return this.http.get<IChatMessage[]>(`${API_BASE_URL}/user/support/messages`, { responseType: 'json' });
  }

  sendSupportMessage(message: string): Observable<boolean> {
    return this.http.post(`${API_BASE_URL}/user/support/send`, { message }, { responseType: 'text', observe: 'response' }).pipe(map((response) => response.status === 200));
  }

  getSupportUsersForAdmin(): Observable<string[]> {
    return this.http.get<string[]>(`${API_BASE_URL}/admin/panel/support/users`, { responseType: 'json' });
  }

  searchSupportCustomersForAdmin(query: string, limit = 20): Observable<ISupportCustomerSummary[]> {
    return this.http.get<ISupportCustomerSummary[]>(`${API_BASE_URL}/admin/panel/support/customers`, { params: { query, limit }, responseType: 'json' });
  }

  getBannedSupportUsersForAdmin(): Observable<string[]> {
    return this.http.get<string[]>(`${API_BASE_URL}/admin/panel/support/banned-users`, { responseType: 'json' });
  }

  getSupportMessagesForAdmin(userMail: string): Observable<IChatMessage[]> {
    return this.http.get<IChatMessage[]>(`${API_BASE_URL}/admin/panel/support/messages`, { params: { userMail }, responseType: 'json' });
  }

  sendSupportReplyFromAdmin(userMail: string, message: string): Observable<boolean> {
    return this.http.post(`${API_BASE_URL}/admin/panel/support/reply`, { userMail, message }, { responseType: 'text', observe: 'response' }).pipe(map((response) => response.status === 200));
  }

  editSupportMessageForAdmin(userMail: string, messageId: number, message: string): Observable<boolean> {
    return this.http.patch(`${API_BASE_URL}/admin/panel/support/messages/${messageId}`, { userMail, message }, { responseType: 'text', observe: 'response' }).pipe(map((response) => response.status === 200));
  }

  deleteSupportMessageForAdmin(userMail: string, messageId: number): Observable<boolean> {
    return this.http.delete(`${API_BASE_URL}/admin/panel/support/messages/${messageId}`, { params: { userMail }, responseType: 'text', observe: 'response' }).pipe(map((response) => response.status === 200));
  }

  closeSupportChatForAdmin(userMail: string): Observable<boolean> {
    return this.http.post(`${API_BASE_URL}/admin/panel/support/close`, null, { params: { userMail }, responseType: 'text', observe: 'response' }).pipe(map((response) => response.status === 200));
  }

  banSupportUserFromAdmin(userMail: string): Observable<boolean> {
    return this.http.post(`${API_BASE_URL}/admin/panel/support/ban`, null, { params: { userMail }, responseType: 'text', observe: 'response' }).pipe(map((response) => response.status === 200));
  }

  unbanSupportUserFromAdmin(userMail: string): Observable<boolean> {
    return this.http.post(`${API_BASE_URL}/admin/panel/support/unban`, null, { params: { userMail }, responseType: 'text', observe: 'response' }).pipe(map((response) => response.status === 200));
  }
}
