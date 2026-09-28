import { Injectable } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { ISupportRealtimeEvent } from '../../interfaces/support/ISupportRealtimeEvent';
import { wsUrl } from '../../app/config/backend.config';

@Injectable({
  providedIn: 'root',
})
export class SupportRealtimeService {
  private socket: WebSocket | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private connectUserMail = '';
  private reconnectEnabled = false;
  private readonly eventsSubject = new Subject<ISupportRealtimeEvent>();
  readonly events$: Observable<ISupportRealtimeEvent> =
    this.eventsSubject.asObservable();

  connect(userMail?: string) {
    this.reconnectEnabled = true;
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
    const supportSocketUrl = wsUrl('/ws/support');
    const socketUrl = normalizedUserMail
      ? `${supportSocketUrl}?userMail=${encodeURIComponent(normalizedUserMail)}`
      : supportSocketUrl;

    const socket = new WebSocket(socketUrl);
    this.socket = socket;

    socket.onmessage = (event: MessageEvent<string>) => {
      try {
        const data = JSON.parse(event.data) as ISupportRealtimeEvent;
        if (data?.type === 'SUPPORT_UPDATED' || data?.type === 'SUPPORT_MESSAGE_CREATED') {
          this.eventsSubject.next(data);
        }
      } catch {
        // ignore malformed message
      }
    };

    socket.onclose = () => {
      // Ignore close events from sockets that were intentionally disconnected
      // or superseded by a newer connection.
      if (this.socket !== socket) {
        return;
      }
      this.socket = null;
      if (this.reconnectEnabled) {
        this.scheduleReconnect();
      }
    };

    socket.onerror = () => {
      // onclose handles reconnect
    };
  }

  disconnect() {
    this.reconnectEnabled = false;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }

    if (this.socket) {
      const socket = this.socket;
      this.socket = null;
      socket.close();
    }

    this.connectUserMail = '';
  }

  private scheduleReconnect() {
    if (this.reconnectTimer) {
      return;
    }

    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (this.reconnectEnabled) {
        this.connect();
      }
    }, 3000);
  }
}

