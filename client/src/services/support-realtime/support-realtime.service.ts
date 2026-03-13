import { Injectable } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { ISupportRealtimeEvent } from '../../interfaces/support/ISupportRealtimeEvent';

const WS_BASE_URL = 'wss://migros-app.onrender.com';

@Injectable({
  providedIn: 'root',
})
export class SupportRealtimeService {
  private socket: WebSocket | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private connectUserMail = '';
  private readonly eventsSubject = new Subject<ISupportRealtimeEvent>();
  readonly events$: Observable<ISupportRealtimeEvent> =
    this.eventsSubject.asObservable();

  connect(userMail?: string) {
    if (typeof userMail === 'string') {
      this.connectUserMail = userMail;
    }
    if (
      this.socket &&
      (this.socket.readyState === WebSocket.OPEN ||
        this.socket.readyState === WebSocket.CONNECTING)
    ) {
      return;
    }

    const normalizedUserMail = (this.connectUserMail || '').trim().toLowerCase();
    const socketUrl = normalizedUserMail
      ? `${WS_BASE_URL}/ws/support?userMail=${encodeURIComponent(normalizedUserMail)}`
      : `${WS_BASE_URL}/ws/support`;

    this.socket = new WebSocket(socketUrl);

    this.socket.onmessage = (event: MessageEvent<string>) => {
      try {
        const data = JSON.parse(event.data) as ISupportRealtimeEvent;
        if (data?.type === 'SUPPORT_UPDATED' || data?.type === 'SUPPORT_MESSAGE_CREATED') {
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

    this.connectUserMail = '';
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

