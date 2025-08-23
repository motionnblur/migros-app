import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { map } from 'rxjs';

@Injectable({
  providedIn: 'root',
})
export class SignService {
  constructor(private http: HttpClient) {}

  adminLogin(adminName: string, adminPassword: string) {
    //const token = localStorage.getItem('admin-token');
    //const headers = new HttpHeaders({
    //  'Content-Type': 'application/json',
    //  Authorization: `Bearer ${token}`,
    //});

    return this.http
      .post(
        'http://localhost:8080/admin/login',
        {
          adminName,
          adminPassword,
        },
        {
          //headers: headers,
          responseType: 'text',
          observe: 'response',
        }
      )
      .pipe(map((response) => response.body));
  }
}
