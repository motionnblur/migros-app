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
      .get<UserSessionResponse>('https://migros-app.onrender.com/user/session')
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
      .get<AdminSessionResponse>('https://migros-app.onrender.com/admin/session')
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

  public logout(onComplete?: () => void): void {
    this.http.post('https://migros-app.onrender.com/user/logout', {}).subscribe({
      next: () => {
        this.userMail = '';
        this.userLoggedInSubject.next(false);
        onComplete?.();
      },
      error: () => {
        this.userMail = '';
        this.userLoggedInSubject.next(false);
        onComplete?.();
      },
    });
  }

  public logoutAdmin(onComplete?: () => void): void {
    this.http.post('https://migros-app.onrender.com/admin/logout', {}).subscribe({
      next: () => {
        this.adminName = '';
        this.adminLoggedInSubject.next(false);
        onComplete?.();
      },
      error: () => {
        this.adminName = '';
        this.adminLoggedInSubject.next(false);
        onComplete?.();
      },
    });
  }
}
