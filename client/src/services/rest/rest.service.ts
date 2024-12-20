import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';

@Injectable({
  providedIn: 'root',
})
export class RestService {
  constructor(private http: HttpClient) {}

  getHello() {
    return this.http.get('http://localhost:8080/hello', {
      responseType: 'text',
    });
  }
  getItemPageData() {
    return this.http.get(`http://localhost:8080/user/supply/getItemPageData`, {
      responseType: 'json',
    });
  }
}
