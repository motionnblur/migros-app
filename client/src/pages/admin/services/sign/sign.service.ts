import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { map } from 'rxjs';
import { apiUrl } from '../../../../app/config/backend.config';

@Injectable({
  providedIn: 'root',
})
export class SignService {
  constructor(private http: HttpClient) {}

  adminLogin(adminName: string, adminPassword: string) {
    return this.http
      .post(
        apiUrl('/admin/login'),
        {
          adminName,
          adminPassword,
        },
        {
          responseType: 'text',
          observe: 'response',
        }
      )
      .pipe(map((response) => response.status === 200));
  }
}
