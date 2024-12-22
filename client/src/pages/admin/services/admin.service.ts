import { Injectable } from '@angular/core';
import { Observable, Subject } from 'rxjs';

@Injectable({
  providedIn: 'root',
})
export class AdminService {
  private loginPhaseStatus = new Subject<boolean>();
  private loginCompleted = new Subject<boolean>();

  getLoginPhaseStatus(): Observable<boolean> {
    return this.loginPhaseStatus.asObservable();
  }
  getLoginCompletedStatus(): Observable<boolean> {
    return this.loginCompleted.asObservable();
  }

  setLoginPhase(status: boolean): void {
    this.loginPhaseStatus.next(status);
  }
  setLoginCompleted(status: boolean): void {
    this.loginCompleted.next(status);
    localStorage.setItem('isLoginCompleted', 'true');
  }
}
