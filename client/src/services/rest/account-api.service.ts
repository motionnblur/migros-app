import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { API_BASE_URL } from '../../app/config/backend.config';
import { ISignDto } from '../../interfaces/ISignDto';
import { IUserProfileTable } from '../../interfaces/IUserProfileTable';

@Injectable({ providedIn: 'root' })
export class AccountApiService {
  constructor(private readonly http: HttpClient) {}

  getVerifyUser(userMail: string): Observable<Object> {
    return this.http.post(`${API_BASE_URL}/user/verifyUserMail`, null, { params: { userMail }, responseType: 'json' });
  }

  uploadUserProfileTableData(table: IUserProfileTable): Observable<boolean> {
    const formData = new FormData();
    formData.append('userFirstName', table.userFirstName);
    formData.append('userLastName', table.userLastName);
    formData.append('userAddress', table.userAddress);
    formData.append('userAddress2', table.userAddress2);
    formData.append('userTown', table.userTown);
    formData.append('userCountry', table.userCountry);
    formData.append('userPostalCode', table.userPostalCode);
    return this.http.post(`${API_BASE_URL}/user/profile/uploadUserProfileTable`, formData, { responseType: 'text', observe: 'response' }).pipe(map((response) => response.status === 200));
  }

  getUserProfileTableData(): Observable<IUserProfileTable> {
    return this.http.get<IUserProfileTable>(`${API_BASE_URL}/user/profile/getUserProfileTable`, { responseType: 'json' });
  }

  signUser(userSignDto: ISignDto): Observable<boolean> {
    return this.http.post(`${API_BASE_URL}/user/signup`, userSignDto, { responseType: 'text', observe: 'response' }).pipe(map((response) => response.status === 200));
  }

  loginUser(userLoginDto: ISignDto): Observable<boolean> {
    return this.http.post(`${API_BASE_URL}/user/login`, userLoginDto, { responseType: 'text', observe: 'response' }).pipe(map((response) => response.status === 200));
  }

  resetPassword(payload: { token: string; userPassword: string }): Observable<boolean> {
    return this.http.post(`${API_BASE_URL}/user/resetPassword`, payload, { responseType: 'text', observe: 'response' }).pipe(map((response) => response.status === 200));
  }
}
