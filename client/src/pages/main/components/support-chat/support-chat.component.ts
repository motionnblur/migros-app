import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';

import { RestService } from '../../../../services/rest/rest.service';
import { AuthService } from '../../../../services/auth/auth.service';
import { SupportRealtimeService } from '../../../../services/support-realtime/support-realtime.service';
import { IChatMessage } from '../../../../interfaces/IChatMessage';
import { ISupportRealtimeEvent } from '../../../../interfaces/support/ISupportRealtimeEvent';

@Component({
  selector: 'app-support-chat',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './support-chat.component.html',
  styleUrl: './support-chat.component.css',
})
export class SupportChatComponent implements OnInit, OnDestroy {
  supportMessages: IChatMessage[] = [];
  supportMessageInput = '';
  isSupportLoading = false;
  isSupportSending = false;
  supportErrorMessage = '';
  private supportPollingIntervalId: ReturnType<typeof setInterval> | null = null;
  private currentUserMail = '';
  private supportRealtimeSub: Subscription | null = null;

  constructor(
    private restService: RestService,
    private authService: AuthService,
    private supportRealtimeService: SupportRealtimeService,
    private router: Router,
    private route: ActivatedRoute
  ) {}

  ngOnInit(): void {
    this.authService.refreshUserSession().subscribe((isLoggedIn) => {
      if (!isLoggedIn) {
        this.router.navigate(
          [{ outlets: { modal: ['login'] } }],
          { relativeTo: this.route.parent ?? this.route }
        );
        return;
      }

      this.currentUserMail = this.authService.getUserMail();

      this.supportRealtimeService.connect(this.currentUserMail);
      this.supportRealtimeSub = this.supportRealtimeService.events$.subscribe(
        (event: ISupportRealtimeEvent) => {
          const normalizedCurrent = (this.currentUserMail || '').trim().toLowerCase();
          const normalizedEvent = (event?.userMail || '').trim().toLowerCase();
          if (!normalizedCurrent || normalizedEvent !== normalizedCurrent) {
            return;
          }

          if (
            event.type === 'SUPPORT_UPDATED' ||
            event.type === 'SUPPORT_MESSAGE_CREATED'
          ) {
            this.loadSupportMessages();
          }
        }
      );

      this.loadSupportMessages();
      this.startSupportMessagePolling();
    });
  }

  ngOnDestroy(): void {
    this.stopSupportMessagePolling();
    this.supportRealtimeSub?.unsubscribe();
  }

  public closeSupportChat() {
    this.router.navigate([{ outlets: { modal: null } }], {
      relativeTo: this.route.parent ?? this.route,
    });
  }

  public sendSupportMessage() {
    const message = this.supportMessageInput.trim();
    if (!message || this.isSupportSending) {
      return;
    }

    this.isSupportSending = true;
    this.restService.sendSupportMessage(message).subscribe({
      next: () => {
        this.supportMessageInput = '';
        this.isSupportSending = false;
        this.loadSupportMessages();
      },
      error: (err) => {
        this.isSupportSending = false;
        this.supportErrorMessage =
          typeof err?.error === 'string' && err.error
            ? err.error
            : 'Mesaj gonderilemedi. Lutfen tekrar deneyin.';
      },
    });
  }

  private loadSupportMessages() {
    this.isSupportLoading = true;
    this.supportErrorMessage = '';

    this.restService.getSupportMessages().subscribe({
      next: (messages: IChatMessage[]) => {
        this.supportMessages = messages;
        this.updateLastSeenManagementMessage(messages);
        this.isSupportLoading = false;
      },
      error: (err) => {
        this.isSupportLoading = false;
        this.supportErrorMessage =
          typeof err?.error === 'string' && err.error
            ? err.error
            : 'Mesajlar yuklenemedi. Lutfen tekrar deneyin.';
      },
    });
  }

  private startSupportMessagePolling() {
    this.stopSupportMessagePolling();
    this.supportPollingIntervalId = setInterval(() => {
      this.loadSupportMessages();
    }, 5000);
  }

  private stopSupportMessagePolling() {
    if (this.supportPollingIntervalId) {
      clearInterval(this.supportPollingIntervalId);
      this.supportPollingIntervalId = null;
    }
  }

  private updateLastSeenManagementMessage(messages: IChatMessage[]) {
    const normalizedUserMail = (this.currentUserMail || '').trim().toLowerCase();
    if (!normalizedUserMail) {
      return;
    }

    const latestManagementId = messages
      .filter((message) => message.sender === 'MANAGEMENT')
      .reduce((maxId, message) => Math.max(maxId, message.id), 0);

    if (latestManagementId > 0) {
      localStorage.setItem(this.getLastSeenKey(normalizedUserMail), String(latestManagementId));
    }
  }

  private getLastSeenKey(userMail: string) {
    return `support:lastSeenManagementMessageId:${userMail}`;
  }
}
