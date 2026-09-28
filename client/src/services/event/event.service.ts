import { Injectable } from '@angular/core';
import {
  EventCallback,
  EventManager,
  EventName,
} from '../../classes/EventManager';

@Injectable({
  providedIn: 'root',
})
export class EventService {
  private eventManager: EventManager;

  constructor() {
    this.eventManager = new EventManager();
  }

  on<K extends EventName>(eventName: K, callback: EventCallback<K>): void {
    this.eventManager.on(eventName, callback);
  }

  off<K extends EventName>(eventName: K, callback: EventCallback<K>): void {
    this.eventManager.off(eventName, callback);
  }

  trigger(eventName: 'productChanged', data: number): void;
  trigger(eventName: 'productAdded' | 'editorOpened', data?: undefined): void;
  trigger(eventName: EventName, data?: number): void {
    if (eventName === 'productChanged') {
      this.eventManager.trigger(eventName, data as number);
    } else {
      this.eventManager.trigger(eventName, data as undefined);
    }
  }
}
