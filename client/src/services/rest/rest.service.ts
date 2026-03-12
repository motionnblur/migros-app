import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { map } from 'rxjs';
import { IProductUploader } from '../../interfaces/IProductUploader';
import { IProductUpdater } from '../../interfaces/IProductUpdater';
import { IAdminProductPreview } from '../../interfaces/IAdminProductPreview';
import { IProductData } from '../../interfaces/IProductData';
import { IProductDescription } from '../../interfaces/IProductDescription';
import { ISubCategory } from '../../interfaces/ISubCategory';
import { ISignDto } from '../../interfaces/ISignDto';
import { IUserCartItemDto } from '../../interfaces/IUserCartItemDto';
import { IOrder } from '../../interfaces/IOrder';
import { IOrderPage } from '../../interfaces/IOrderPage';
import { IUserProfileTable } from '../../interfaces/IUserProfileTable';
import { IChatMessage } from '../../interfaces/IChatMessage';
import { IUserOrderDetail } from '../../interfaces/IUserOrderDetail';
import { IUserOrderGroup } from '../../interfaces/IUserOrderGroup';
import { IProductPreview } from '../../interfaces/IProductPreview';
import { ISupportCustomerSummary } from '../../interfaces/support/ISupportCustomerSummary';

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
  getProductPageDataAdmin(
    categoryId: number,
    page: number,
    productRange: number
  ) {
    return this.http.get(
      `http://localhost:8080/admin/supply/getProductsFromCategory`,
      {
        params: { categoryId, page, productRange },
        responseType: 'json',
      }
    );
  }
  getProductCountsFromCategory(categoryId: number) {
    return this.http.get(
      `http://localhost:8080/user/supply/getProductCountsFromCategory`,
      {
        params: { categoryId },
        responseType: 'json',
      }
    );
  }
  getProductCountsFromCategoryAdmin(categoryId: number) {
    return this.http.get(
      `http://localhost:8080/admin/supply/getProductCountsFromCategory`,
      {
        params: { categoryId },
        responseType: 'json',
      }
    );
  }
  getProductCountsFromSubCategory(subcategoryName: string) {
    return this.http.get(
      `http://localhost:8080/user/supply/getProductCountsFromSubCategory`,
      {
        params: { subcategoryName },
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
    return this.http
      .get(`http://localhost:8080/user/supply/getProductDataWithProductId`, {
        params: { productId },
        responseType: 'json',
      })
      .pipe(map((response) => response as IProductData));
  }
  getProductDataForUserCart(productId: number) {
    return this.http
      .get(`http://localhost:8080/user/supply/getProductData`, {
        params: { productId },
        responseType: 'json',
      })
      .pipe(map((response) => response as IProductData));
  }
  getAllProductsFromUserCart() {
    return this.http
      .get(`http://localhost:8080/user/supply/getProductData`, {
        responseType: 'json',
      })
      .pipe(map((response) => response as IUserCartItemDto[]));
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
    formData.append('productName', this.normalizeStringField(productData.productName));
    formData.append('subCategoryName', this.normalizeStringField(productData.subCategoryName));
    formData.append('productPrice', productData.productPrice.toString());
    formData.append('productCount', productData.productCount.toString());
    formData.append('productDiscount', productData.productDiscount.toString());
    formData.append('productDescription', this.normalizeOptionalStringField(productData.productDescription));
    if (productData.selectedImage) {
      formData.append('selectedImage', productData.selectedImage);
    }
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
    formData.append('productName', this.normalizeStringField(productData.productName));
    formData.append('subCategoryName', this.normalizeStringField(productData.subCategoryName));
    formData.append('productPrice', productData.productPrice.toString());
    formData.append('productCount', productData.productCount.toString());
    formData.append('productDiscount', productData.productDiscount.toString());
    formData.append('productDescription', this.normalizeOptionalStringField(productData.productDescription));
    if (productData.selectedImage) {
      formData.append('selectedImage', productData.selectedImage);
    }
    formData.append('categoryValue', productData.categoryValue.toString());

    return this.http
      .post('http://localhost:8080/admin/panel/updateProduct', formData, {
        responseType: 'text',
        observe: 'response',
      })
      .pipe(map((response) => response.status === 200));
  }
  updateOrderStatus(orderId: number, status: string) {
    return this.http
      .get(`http://localhost:8080/admin/panel/updateOrderStatus`, {
        params: { orderId, status },
        responseType: 'text',
        observe: 'response',
      })
      .pipe(map((response) => response.status === 200));
  }
  uploadUserProfileTableData(table: IUserProfileTable) {
    const formData = new FormData();
    formData.append('userFirstName', table.userFirstName);
    formData.append('userLastName', table.userLastName);
    formData.append('userAddress', table.userAddress);
    formData.append('userAddress2', table.userAddress2);
    formData.append('userTown', table.userTown);
    formData.append('userCountry', table.userCountry);
    formData.append('userPostalCode', table.userPostalCode);

    return this.http
      .post(
        'http://localhost:8080/user/profile/uploadUserProfileTable',
        formData,
        {
          responseType: 'text',
          observe: 'response',
        }
      )
      .pipe(map((response) => response.status === 200));
  }
  getUserProfileTableData() {
    return this.http
      .get(`http://localhost:8080/user/profile/getUserProfileTable`, {
        responseType: 'json',
      })
      .pipe(map((response) => response as IUserProfileTable));
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

  addProductDescription(productDescription: IProductDescription) {
    const data: IProductDescription = {
      productId: productDescription.productId,
      descriptionList: productDescription.descriptionList,
    };
    return this.http
      .post('http://localhost:8080/admin/panel/addProductDescription', data, {
        responseType: 'text',
        observe: 'response',
      })
      .pipe(map((response) => response.status === 200));
  }
  getProductDescription(productId: number) {
    return this.http
      .get(`http://localhost:8080/user/supply/getProductDescription`, {
        params: { productId },
        responseType: 'json',
      })
      .pipe(map((response) => response as IProductDescription));
  }
  deleteProductDescription(descriptionId: number) {
    return this.http
      .delete('http://localhost:8080/admin/panel/deleteProductDescription', {
        params: { descriptionId },
        responseType: 'text',
        observe: 'response',
      })
      .pipe(map((response) => response.status === 200));
  }
  getSubCategories(categoryId: number) {
    return this.http
      .get(`http://localhost:8080/user/supply/getSubCategories`, {
        params: { categoryId },
        responseType: 'json',
      })
      .pipe(map((response) => response as ISubCategory[]));
  }
  getProducstFromSubCategory(
    subcategoryName: string,
    page: number,
    productRange: number
  ) {
    return this.http
      .get(`http://localhost:8080/user/supply/getProductsFromSubcategory`, {
        params: { subcategoryName, page, productRange },
        responseType: 'json',
      })
      .pipe(map((response) => response as IProductPreview[]));
  }
  signUser(userSignDto: ISignDto) {
    return this.http
      .post('http://localhost:8080/user/signup', userSignDto, {
        responseType: 'text',
        observe: 'response',
      })
      .pipe(map((response) => response.status === 200));
  }
  loginUser(userLoginDto: ISignDto) {
    return this.http
      .post('http://localhost:8080/user/login', userLoginDto, {
        responseType: 'text',
        observe: 'response',
      })
      .pipe(map((response) => response.status === 200));
  }
  addProductToUserCart(productId: number) {
    return this.http
      .get(`http://localhost:8080/user/supply/addProductToUserCart`, {
        params: { productId },
        responseType: 'text',
        observe: 'response',
      })
      .pipe(map((response) => response.body));
  }
  removeProductFromUserCart(productId: number) {
    return this.http
      .delete(`http://localhost:8080/user/supply/removeProductFromUserCart`, {
        params: { productId },
        responseType: 'text',
        observe: 'response',
      })
      .pipe(map((response) => response.body));
  }
  updateProductCountInUserCart(productId: number, count: number) {
    return this.http
      .get(`http://localhost:8080/user/supply/updateProductCountInUserCart`, {
        params: { productId, count },
        responseType: 'text',
        observe: 'response',
      })
      .pipe(map((response) => response.body));
  }
  getAllOrders(page: number, productRange: number) {
    return this.http
      .get(`http://localhost:8080/admin/panel/getAllOrders`, {
        params: { page, productRange },
        responseType: 'json',
      })
      .pipe(map((response) => response as IOrderPage));
  }
  getUserProfileData(orderId: number) {
    return this.http
      .get(`http://localhost:8080/admin/panel/getUserProfileData`, {
        params: { orderId },
        responseType: 'json',
      })
      .pipe(map((response) => response as IUserProfileTable));
  }
  getAllOrderIds() {
    return this.http
      .get(`http://localhost:8080/user/supply/getAllOrderIds`, {
        responseType: 'json',
      })
      .pipe(map((response) => response as number[]));
  }
  getOrderStatusByOrderId(orderId: number) {
    return this.http
      .get(`http://localhost:8080/user/supply/getOrderStatusByOrderId`, {
        params: { orderId },
        responseType: 'text',
      })
      .pipe(map((response) => response as string));
  }
  calcelOrder(orderId: number) {
    return this.http
      .delete(`http://localhost:8080/user/supply/cancelOrder`, {
        params: { orderId },
        responseType: 'text',
        observe: 'response',
      })
      .pipe(map((response) => response.status === 200));
  }
  getSupportMessages() {
    return this.http
      .get(`http://localhost:8080/user/support/messages`, {
        responseType: 'json',
      })
      .pipe(map((response) => response as IChatMessage[]));
  }

  sendSupportMessage(message: string) {
    return this.http
      .post(
        `http://localhost:8080/user/support/send`,
        { message },
        {
          responseType: 'text',
          observe: 'response',
        }
      )
      .pipe(map((response) => response.status === 200));
  }

  getSupportUsersForAdmin() {
    return this.http
      .get(`http://localhost:8080/admin/panel/support/users`, {
        responseType: 'json',
      })
      .pipe(map((response) => response as string[]));
  }


  searchSupportCustomersForAdmin(query: string, limit: number = 20) {
    return this.http
      .get(`http://localhost:8080/admin/panel/support/customers`, {
        params: { query, limit },
        responseType: 'json',
      })
      .pipe(map((response) => response as ISupportCustomerSummary[]));
  }

  getBannedSupportUsersForAdmin() {
    return this.http
      .get(`http://localhost:8080/admin/panel/support/banned-users`, {
        responseType: 'json',
      })
      .pipe(map((response) => response as string[]));
  }

  getSupportMessagesForAdmin(userMail: string) {
    return this.http
      .get(`http://localhost:8080/admin/panel/support/messages`, {
        params: { userMail },
        responseType: 'json',
      })
      .pipe(map((response) => response as IChatMessage[]));
  }
  sendSupportReplyFromAdmin(userMail: string, message: string) {
    return this.http
      .post(
        `http://localhost:8080/admin/panel/support/reply`,
        { userMail, message },
        {
          responseType: 'text',
          observe: 'response',
        }
      )
      .pipe(map((response) => response.status === 200));
  }
  editSupportMessageForAdmin(userMail: string, messageId: number, message: string) {
    return this.http
      .patch(
        `http://localhost:8080/admin/panel/support/messages/${messageId}`,
        { userMail, message },
        {
          responseType: 'text',
          observe: 'response',
        }
      )
      .pipe(map((response) => response.status === 200));
  }

  deleteSupportMessageForAdmin(userMail: string, messageId: number) {
    return this.http
      .delete(`http://localhost:8080/admin/panel/support/messages/${messageId}`, {
        params: { userMail },
        responseType: 'text',
        observe: 'response',
      })
      .pipe(map((response) => response.status === 200));
  }
  closeSupportChatForAdmin(userMail: string) {
    return this.http
      .post(
        `http://localhost:8080/admin/panel/support/close`,
        null,
        {
          params: { userMail },
          responseType: 'text',
          observe: 'response',
        }
      )
      .pipe(map((response) => response.status === 200));
  }
  banSupportUserFromAdmin(userMail: string) {
    return this.http
      .post(
        `http://localhost:8080/admin/panel/support/ban`,
        null,
        {
          params: { userMail },
          responseType: 'text',
          observe: 'response',
        }
      )
      .pipe(map((response) => response.status === 200));
  }
  unbanSupportUserFromAdmin(userMail: string) {
    return this.http
      .post(
        `http://localhost:8080/admin/panel/support/unban`,
        null,
        {
          params: { userMail },
          responseType: 'text',
          observe: 'response',
        }
      )
      .pipe(map((response) => response.status === 200));
  }

  deleteOrder(orderId: number) {
    return this.http
      .delete('http://localhost:8080/admin/panel/deleteOrder', {
        params: { orderId },
        responseType: 'text',
        observe: 'response',
      })
      .pipe(map((response) => response.status === 200));
  }
  getUserOrders() {
    return this.http
      .get(`http://localhost:8080/user/supply/getUserOrders`, {
        responseType: 'json',
      })
      .pipe(map((response) => response as IUserOrderDetail[]));
  }
  getUserOrderGroups() {
    return this.http
      .get(`http://localhost:8080/user/supply/getUserOrderGroups`, {
        responseType: 'json',
      })
      .pipe(map((response) => response as IUserOrderGroup[]));
  }

  private normalizeStringField(value: string): string {
    return (value ?? '').trim();
  }

  private normalizeOptionalStringField(value: string): string {
    const normalized = (value ?? '').trim();
    if (normalized.toLowerCase() === 'undefined' || normalized.toLowerCase() === 'null') {
      return '';
    }
    return normalized;
  }
}


