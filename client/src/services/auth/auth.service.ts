import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable, of } from 'rxjs';
import { catchError, map, tap } from 'rxjs/operators';

interface UserSessionResponse {
  userMail: string;
}

interface AdminSessionResponse {
  adminName: string;
}

@Injectable({
  providedIn: 'root',
})
export class AuthService {
  private userLoggedInSubject = new BehaviorSubject<boolean>(false);
  private adminLoggedInSubject = new BehaviorSubject<boolean>(false);
  private userMail = '';
  private adminName = '';

  readonly userLoggedIn$ = this.userLoggedInSubject.asObservable();
  readonly adminLoggedIn$ = this.adminLoggedInSubject.asObservable();

  constructor(private http: HttpClient) {}

  public refreshUserSession(): Observable<boolean> {
    return this.http
      .get<UserSessionResponse>('http://localhost:8080/user/session')
      .pipe(
        tap((session) => {
          this.userMail = session?.userMail ?? '';
          this.userLoggedInSubject.next(!!this.userMail);
        }),
        map((session) => !!session?.userMail),
        catchError(() => {
          this.userMail = '';
          this.userLoggedInSubject.next(false);
          return of(false);
        })
      );
  }

  public refreshAdminSession(): Observable<boolean> {
    return this.http
      .get<AdminSessionResponse>('http://localhost:8080/admin/session')
      .pipe(
        tap((session) => {
          this.adminName = session?.adminName ?? '';
          this.adminLoggedInSubject.next(!!this.adminName);
        }),
        map((session) => !!session?.adminName),
        catchError(() => {
          this.adminName = '';
          this.adminLoggedInSubject.next(false);
          return of(false);
        })
      );
  }

  public isLoggedIn(): boolean {
    return this.userLoggedInSubject.value;
  }

  public isAdminLoggedIn(): boolean {
    return this.adminLoggedInSubject.value;
  }

  public getUserMail(): string {
    return this.userMail;
  }

  public getAdminName(): string {
    return this.adminName;
  }

  public logout(): void {
    this.http.post('http://localhost:8080/user/logout', {}).subscribe({
      next: () => {
        this.userMail = '';
        this.userLoggedInSubject.next(false);
      },
      error: () => {
        this.userMail = '';
        this.userLoggedInSubject.next(false);
      },
    });
  }

  public logoutAdmin(): void {
    this.http.post('http://localhost:8080/admin/logout', {}).subscribe({
      next: () => {
        this.adminName = '';
        this.adminLoggedInSubject.next(false);
      },
      error: () => {
        this.adminName = '';
        this.adminLoggedInSubject.next(false);
      },
    });
  }
}
