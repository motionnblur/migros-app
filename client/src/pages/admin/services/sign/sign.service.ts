import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { map } from 'rxjs';

@Injectable({
  providedIn: 'root',
})
export class SignService {
  constructor(private http: HttpClient) {}

  adminLogin(adminName: string, adminPassword: string) {
    return this.http
      .post(
        'http://localhost:8080/admin/login',
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
