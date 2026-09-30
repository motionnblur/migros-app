import { Injectable } from '@angular/core';
import { AccountApiService } from './account-api.service';
import { AdminApiService } from './admin-api.service';
import { CartOrdersApiService } from './cart-orders-api.service';
import { CatalogApiService } from './catalog-api.service';
import { PaymentApiService } from './payment-api.service';
import { SupportApiService } from './support-api.service';
import { IProductUploader } from '../../interfaces/IProductUploader';
import { IProductUpdater } from '../../interfaces/IProductUpdater';
import { IProductDescription } from '../../interfaces/IProductDescription';
import { ISignDto } from '../../interfaces/ISignDto';
import { IUserProfileTable } from '../../interfaces/IUserProfileTable';

/**
 * Compatibility facade for existing components and tests. New API requests
 * belong in the focused domain services injected below.
 */
@Injectable({ providedIn: 'root' })
export class RestService {
  constructor(
    private readonly catalogApi: CatalogApiService,
    private readonly cartOrdersApi: CartOrdersApiService,
    private readonly paymentApi: PaymentApiService,
    private readonly accountApi: AccountApiService,
    private readonly adminApi: AdminApiService,
    private readonly supportApi: SupportApiService,
  ) {}

  getVerifyUser(userMail: string): ReturnType<AccountApiService['getVerifyUser']> {
    return this.accountApi.getVerifyUser(userMail);
  }

  getProductPageData(categoryId: number, page: number, productRange: number): ReturnType<CatalogApiService['getProductPageData']> {
    return this.catalogApi.getProductPageData(categoryId, page, productRange);
  }

  getProductPageDataAdmin(categoryId: number, page: number, productRange: number): ReturnType<AdminApiService['getProductPageDataAdmin']> {
    return this.adminApi.getProductPageDataAdmin(categoryId, page, productRange);
  }

  getProductCountsFromCategory(categoryId: number): ReturnType<CatalogApiService['getProductCountsFromCategory']> {
    return this.catalogApi.getProductCountsFromCategory(categoryId);
  }

  getProductCountsFromCategoryAdmin(categoryId: number): ReturnType<AdminApiService['getProductCountsFromCategoryAdmin']> {
    return this.adminApi.getProductCountsFromCategoryAdmin(categoryId);
  }

  getAllProductsAdmin(page: number, productRange: number): ReturnType<AdminApiService['getAllProductsAdmin']> {
    return this.adminApi.getAllProductsAdmin(page, productRange);
  }

  getAllProductCountsAdmin(): ReturnType<AdminApiService['getAllProductCountsAdmin']> {
    return this.adminApi.getAllProductCountsAdmin();
  }

  getProductCountsFromSubCategory(subcategoryName: string): ReturnType<CatalogApiService['getProductCountsFromSubCategory']> {
    return this.catalogApi.getProductCountsFromSubCategory(subcategoryName);
  }

  getProductImage(productId: number): ReturnType<CatalogApiService['getProductImage']> {
    return this.catalogApi.getProductImage(productId);
  }

  getProductData(productId: number): ReturnType<CatalogApiService['getProductData']> {
    return this.catalogApi.getProductData(productId);
  }

  getProductDataForUserCart(productId: number): ReturnType<CatalogApiService['getProductDataForUserCart']> {
    return this.catalogApi.getProductDataForUserCart(productId);
  }

  getAllProductsFromUserCart(): ReturnType<CartOrdersApiService['getAllProductsFromUserCart']> {
    return this.cartOrdersApi.getAllProductsFromUserCart();
  }

  reconcileUserCart(): ReturnType<CartOrdersApiService['reconcileUserCart']> {
    return this.cartOrdersApi.reconcileUserCart();
  }

  getAllAdminProducts(adminId: number, page: number, productRange: number): ReturnType<AdminApiService['getAllAdminProducts']> {
    return this.adminApi.getAllAdminProducts(adminId, page, productRange);
  }

  uploadProductData(productData: IProductUploader): ReturnType<AdminApiService['uploadProductData']> {
    return this.adminApi.uploadProductData(productData);
  }

  updateProductData(productData: IProductUpdater): ReturnType<AdminApiService['updateProductData']> {
    return this.adminApi.updateProductData(productData);
  }

  updateOrderStatus(orderId: number, status: string): ReturnType<AdminApiService['updateOrderStatus']> {
    return this.adminApi.updateOrderStatus(orderId, status);
  }

  uploadUserProfileTableData(table: IUserProfileTable): ReturnType<AccountApiService['uploadUserProfileTableData']> {
    return this.accountApi.uploadUserProfileTableData(table);
  }

  getUserProfileTableData(): ReturnType<AccountApiService['getUserProfileTableData']> {
    return this.accountApi.getUserProfileTableData();
  }

  deleteProduct(productId: number): ReturnType<AdminApiService['deleteProduct']> {
    return this.adminApi.deleteProduct(productId);
  }

  addProductDescription(productDescription: IProductDescription): ReturnType<AdminApiService['addProductDescription']> {
    return this.adminApi.addProductDescription(productDescription);
  }

  getProductDescription(productId: number): ReturnType<CatalogApiService['getProductDescription']> {
    return this.catalogApi.getProductDescription(productId);
  }

  deleteProductDescription(descriptionId: number): ReturnType<AdminApiService['deleteProductDescription']> {
    return this.adminApi.deleteProductDescription(descriptionId);
  }

  getSubCategories(categoryId: number): ReturnType<CatalogApiService['getSubCategories']> {
    return this.catalogApi.getSubCategories(categoryId);
  }

  getProducstFromSubCategory(subcategoryName: string, page: number, productRange: number): ReturnType<CatalogApiService['getProducstFromSubCategory']> {
    return this.catalogApi.getProducstFromSubCategory(subcategoryName, page, productRange);
  }

  signUser(userSignDto: ISignDto): ReturnType<AccountApiService['signUser']> {
    return this.accountApi.signUser(userSignDto);
  }

  loginUser(userLoginDto: ISignDto): ReturnType<AccountApiService['loginUser']> {
    return this.accountApi.loginUser(userLoginDto);
  }

  resetPassword(payload: { token: string; userPassword: string }): ReturnType<AccountApiService['resetPassword']> {
    return this.accountApi.resetPassword(payload);
  }

  addProductToUserCart(productId: number): ReturnType<CartOrdersApiService['addProductToUserCart']> {
    return this.cartOrdersApi.addProductToUserCart(productId);
  }

  removeProductFromUserCart(productId: number): ReturnType<CartOrdersApiService['removeProductFromUserCart']> {
    return this.cartOrdersApi.removeProductFromUserCart(productId);
  }

  updateProductCountInUserCart(productId: number, count: number): ReturnType<CartOrdersApiService['updateProductCountInUserCart']> {
    return this.cartOrdersApi.updateProductCountInUserCart(productId, count);
  }

  prepareCheckout(): ReturnType<PaymentApiService['prepareCheckout']> {
    return this.paymentApi.prepareCheckout();
  }

  getCheckoutStatus(checkoutId: string): ReturnType<PaymentApiService['getCheckoutStatus']> {
    return this.paymentApi.getCheckoutStatus(checkoutId);
  }

  getPaymentStatus(checkoutId: string): ReturnType<PaymentApiService['getPaymentStatus']> {
    return this.paymentApi.getPaymentStatus(checkoutId);
  }

  chargeCheckout(checkoutId: string, token: string): ReturnType<PaymentApiService['chargeCheckout']> {
    return this.paymentApi.chargeCheckout(checkoutId, token);
  }

  cancelCheckout(checkoutId: string): ReturnType<PaymentApiService['cancelCheckout']> {
    return this.paymentApi.cancelCheckout(checkoutId);
  }

  getAllOrders(page: number, productRange: number): ReturnType<AdminApiService['getAllOrders']> {
    return this.adminApi.getAllOrders(page, productRange);
  }

  getUserProfileData(orderId: number): ReturnType<AdminApiService['getUserProfileData']> {
    return this.adminApi.getUserProfileData(orderId);
  }

  getAllOrderIds(): ReturnType<CartOrdersApiService['getAllOrderIds']> {
    return this.cartOrdersApi.getAllOrderIds();
  }

  getOrderStatusByOrderId(orderId: number): ReturnType<CartOrdersApiService['getOrderStatusByOrderId']> {
    return this.cartOrdersApi.getOrderStatusByOrderId(orderId);
  }

  calcelOrder(orderId: number): ReturnType<CartOrdersApiService['calcelOrder']> {
    return this.cartOrdersApi.calcelOrder(orderId);
  }

  getSupportMessages(): ReturnType<SupportApiService['getSupportMessages']> {
    return this.supportApi.getSupportMessages();
  }

  sendSupportMessage(message: string): ReturnType<SupportApiService['sendSupportMessage']> {
    return this.supportApi.sendSupportMessage(message);
  }

  getSupportUsersForAdmin(): ReturnType<SupportApiService['getSupportUsersForAdmin']> {
    return this.supportApi.getSupportUsersForAdmin();
  }

  searchSupportCustomersForAdmin(query: string, limit = 20): ReturnType<SupportApiService['searchSupportCustomersForAdmin']> {
    return this.supportApi.searchSupportCustomersForAdmin(query, limit);
  }

  getBannedSupportUsersForAdmin(): ReturnType<SupportApiService['getBannedSupportUsersForAdmin']> {
    return this.supportApi.getBannedSupportUsersForAdmin();
  }

  getSupportMessagesForAdmin(userMail: string): ReturnType<SupportApiService['getSupportMessagesForAdmin']> {
    return this.supportApi.getSupportMessagesForAdmin(userMail);
  }

  sendSupportReplyFromAdmin(userMail: string, message: string): ReturnType<SupportApiService['sendSupportReplyFromAdmin']> {
    return this.supportApi.sendSupportReplyFromAdmin(userMail, message);
  }

  editSupportMessageForAdmin(userMail: string, messageId: number, message: string): ReturnType<SupportApiService['editSupportMessageForAdmin']> {
    return this.supportApi.editSupportMessageForAdmin(userMail, messageId, message);
  }

  deleteSupportMessageForAdmin(userMail: string, messageId: number): ReturnType<SupportApiService['deleteSupportMessageForAdmin']> {
    return this.supportApi.deleteSupportMessageForAdmin(userMail, messageId);
  }

  closeSupportChatForAdmin(userMail: string): ReturnType<SupportApiService['closeSupportChatForAdmin']> {
    return this.supportApi.closeSupportChatForAdmin(userMail);
  }

  banSupportUserFromAdmin(userMail: string): ReturnType<SupportApiService['banSupportUserFromAdmin']> {
    return this.supportApi.banSupportUserFromAdmin(userMail);
  }

  unbanSupportUserFromAdmin(userMail: string): ReturnType<SupportApiService['unbanSupportUserFromAdmin']> {
    return this.supportApi.unbanSupportUserFromAdmin(userMail);
  }

  deleteOrder(orderId: number): ReturnType<AdminApiService['deleteOrder']> {
    return this.adminApi.deleteOrder(orderId);
  }

  getUserOrders(): ReturnType<CartOrdersApiService['getUserOrders']> {
    return this.cartOrdersApi.getUserOrders();
  }

  getUserOrderGroups(): ReturnType<CartOrdersApiService['getUserOrderGroups']> {
    return this.cartOrdersApi.getUserOrderGroups();
  }
}
