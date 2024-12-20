import { Injectable } from '@angular/core';
import { EventManager } from '../../classes/EventManager';

@Injectable({
  providedIn: 'root',
})
export class EventService {
  private eventManager: EventManager;

  constructor() {
    this.eventManager = new EventManager();
  }

  on(eventName: string, callback: (data: any) => void): void {
    this.eventManager.on(eventName, callback);
  }

  off(eventName: string, callback: (data: any) => void): void {
    this.eventManager.off(eventName, callback);
  }

  trigger(eventName: string, data?: any): void {
    this.eventManager.trigger(eventName, data);
  }
}
