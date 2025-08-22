import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';

@Injectable({
  providedIn: 'root',
})
export class SignService {
  constructor(private http: HttpClient) {}

  adminLogin(adminName: string, adminPassword: string) {
    return this.http.post('http://localhost:8080/admin/login', {
      adminName,
      adminPassword,
    });
  }
}
