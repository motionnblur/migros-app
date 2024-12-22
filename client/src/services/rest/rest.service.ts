import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { map } from 'rxjs';

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
  getItemPageData(categoryId: number, page: number, itemRange: number) {
    return this.http.get(
      `http://localhost:8080/user/supply/getItemsFromCategory`,
      {
        params: { categoryId, page, itemRange },
        responseType: 'json',
      }
    );
  }
  uploadImage(image: File) {
    const formData = new FormData();
    formData.append('file', image);
    return this.http.post(
      'http://localhost:8080/admin/panel/uploadImage',
      formData,
      {
        responseType: 'text',
      }
    );
  }
}
