import { Injectable } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { ISupportRealtimeEvent } from '../../interfaces/support/ISupportRealtimeEvent';

@Injectable({
  providedIn: 'root',
})
export class SupportRealtimeService {
  private socket: WebSocket | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly eventsSubject = new Subject<ISupportRealtimeEvent>();
  readonly events$: Observable<ISupportRealtimeEvent> =
    this.eventsSubject.asObservable();

  connect() {
    if (
      this.socket &&
      (this.socket.readyState === WebSocket.OPEN ||
        this.socket.readyState === WebSocket.CONNECTING)
    ) {
      return;
    }

    this.socket = new WebSocket('ws://localhost:8080/ws/support');

    this.socket.onmessage = (event: MessageEvent<string>) => {
      try {
        const data = JSON.parse(event.data) as ISupportRealtimeEvent;
        if (data?.type === 'SUPPORT_UPDATED') {
          this.eventsSubject.next(data);
        }
      } catch {
        // ignore malformed message
      }
    };

    this.socket.onclose = () => {
      this.socket = null;
      this.scheduleReconnect();
    };

    this.socket.onerror = () => {
      // onclose handles reconnect
    };
  }

  disconnect() {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }

    if (this.socket) {
      this.socket.close();
      this.socket = null;
    }
  }

  private scheduleReconnect() {
    if (this.reconnectTimer) {
      return;
    }

    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, 3000);
  }
}
