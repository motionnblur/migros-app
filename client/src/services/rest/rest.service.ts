import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { map } from 'rxjs';
import { IProductUploader } from '../../interfaces/IProductUploader';
import { IProductUpdater } from '../../interfaces/IProductUpdater';
import { IAdminProductPreview } from '../../interfaces/IAdminProductPreview';

@Injectable({
  providedIn: 'root',
})
export class RestService {
  constructor(private http: HttpClient) {}

  getProductPageData(categoryId: number, page: number, productRange: number) {
    return this.http.get(
      `http://localhost:8080/user/supply/getProductsFromCategory`,
      {
        params: { categoryId, page, productRange },
        responseType: 'json',
      }
    );
  }
  getProductImage(productId: number) {
    return this.http.get(`http://localhost:8080/user/supply/getProductImage`, {
      params: { productId },
      responseType: 'blob',
    });
  }
  getProductData(productId: number) {
    return this.http.get(`http://localhost:8080/admin/panel/getProductData`, {
      params: { productId },
      responseType: 'json',
    });
  }
  getAllAdminProducts(adminId: number, page: number, productRange: number) {
    return this.http
      .get(`http://localhost:8080/admin/panel/getAllAdminProducts`, {
        params: {
          adminId,
          page,
          productRange,
        },
        responseType: 'json',
      })
      .pipe(map((response) => response as IAdminProductPreview[]));
  }
  uploadProductData(productData: IProductUploader) {
    const formData = new FormData();
    formData.append('adminId', productData.adminId.toString());
    formData.append('productName', productData.productName);
    formData.append('productPrice', productData.productPrice.toString());
    formData.append('productCount', productData.productCount.toString());
    formData.append('productDiscount', productData.productDiscount.toString());
    formData.append('productDescription', productData.productDescription);
    formData.append('selectedImage', productData.selectedImage!);
    formData.append('categoryValue', productData.categoryValue.toString());

    return this.http
      .post('http://localhost:8080/admin/panel/uploadProduct', formData, {
        responseType: 'text',
        observe: 'response',
      })
      .pipe(map((response) => response.status === 200));
  }
  updateProductData(productData: IProductUpdater) {
    const formData = new FormData();
    formData.append('adminId', productData.adminId.toString());
    formData.append('productId', productData.productId.toString());
    formData.append('productName', productData.productName);
    formData.append('productPrice', productData.productPrice.toString());
    formData.append('productCount', productData.productCount.toString());
    formData.append('productDiscount', productData.productDiscount.toString());
    formData.append('productDescription', productData.productDescription);
    formData.append('selectedImage', productData.selectedImage!);
    formData.append('categoryValue', productData.categoryValue.toString());

    return this.http
      .post('http://localhost:8080/admin/panel/updateProduct', formData, {
        responseType: 'text',
        observe: 'response',
      })
      .pipe(map((response) => response.status === 200));
  }

  deleteProduct(productId: number) {
    return this.http
      .delete('http://localhost:8080/admin/panel/deleteProduct', {
        params: { productId },
        responseType: 'text',
        observe: 'response',
      })
      .pipe(map((response) => response.status === 200));
  }
}
