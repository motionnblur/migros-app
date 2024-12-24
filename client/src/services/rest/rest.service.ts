import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { map } from 'rxjs';
import { IProductUploader } from '../../interfaces/IProductUploader';

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
  getItemImage(itemId: number) {
    return this.http.get(`http://localhost:8080/user/supply/getItemImage`, {
      params: { itemId },
      responseType: 'blob',
    });
  }
  getAllAdminProducts(adminId: number, page: number, itemRange: number) {
    return this.http.get(
      `http://localhost:8080/admin/panel/getAllAdminProducts`,
      {
        params: {
          adminId,
          page,
          itemRange,
        },
        responseType: 'json',
      }
    );
  }
  uploadProductData(productData: IProductUploader) {
    const formData = new FormData();
    formData.append('adminId', productData.adminId.toString());
    formData.append('productName', productData.productName);
    formData.append('price', productData.price.toString());
    formData.append('count', productData.count.toString());
    formData.append('discount', productData.discount.toString());
    formData.append('description', productData.description);
    formData.append('selectedImage', productData.selectedImage!);
    formData.append('categoryValue', productData.categoryValue.toString());

    return this.http
      .post('http://localhost:8080/admin/panel/uploadProduct', formData, {
        responseType: 'text',
        observe: 'response',
      })
      .pipe(map((response) => response.status === 200));
  }

  deleteProduct(itemId: number) {
    return this.http
      .delete('http://localhost:8080/admin/panel/deleteProduct', {
        params: { itemId },
        responseType: 'text',
        observe: 'response',
      })
      .pipe(map((response) => response.status === 200));
  }
}
