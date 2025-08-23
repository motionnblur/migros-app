import { Injectable } from '@angular/core';

@Injectable({
  providedIn: 'root',
})
export class AuthService {
  constructor() {}

  public setToken(token: string) {
    localStorage.setItem('token', token);
  }
  public getToken() {
    return localStorage.getItem('token');
  }

  public setAdminToken(token: string) {
    localStorage.setItem('admin-token', token);
  }
  public getAdminToken() {
    return localStorage.getItem('admin-token');
  }

  public isLoggedIn() {
    const token = this.getToken();
    if (!token) return false;
    return !this.isTokenExpired(token);
  }
  public isTokenExists() {
    const token = this.getToken();
    return token !== null;
  }
  public logout() {
    localStorage.removeItem('token');
  }
  decodeToken(token: string): any {
    try {
      const payload = token.split('.')[1];
      return JSON.parse(atob(payload));
    } catch (error) {
      return null;
    }
  }
  isTokenExpired(token: string): boolean {
    const decodedToken = this.decodeToken(token);
    if (!decodedToken?.exp) return false;
    const expirationDate = new Date(0);
    expirationDate.setUTCSeconds(decodedToken.exp);
    return expirationDate < new Date();
  }
}
