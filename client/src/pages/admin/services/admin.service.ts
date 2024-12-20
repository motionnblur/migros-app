import { Injectable } from '@angular/core';
import { Observable, Subject } from 'rxjs';

@Injectable({
  providedIn: 'root',
})
export class AdminService {
  private loginPhaseStatus = new Subject<boolean>();

  getLoginPhaseStatus(): Observable<boolean> {
    return this.loginPhaseStatus.asObservable();
  }

  setLoginPhase(status: boolean): void {
    this.loginPhaseStatus.next(status);
  }
}
