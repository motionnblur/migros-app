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
    if (!this.authService.isLoggedIn()) {
      this.router.navigate(
        [{ outlets: { modal: ['login'] } }],
        { relativeTo: this.route.parent ?? this.route }
      );
      return;
    }

    this.updateCurrentUserMailFromToken();

    this.supportRealtimeService.connect();
    this.supportRealtimeSub = this.supportRealtimeService.events$.subscribe(
      (event: ISupportRealtimeEvent) => {
        if (!this.currentUserMail) {
          return;
        }

        if (event.userMail === this.currentUserMail) {
          this.loadSupportMessages();
        }
      }
    );

    this.loadSupportMessages();
    this.startSupportMessagePolling();
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

  private updateCurrentUserMailFromToken() {
    const token = this.authService.getToken();
    if (!token) {
      this.currentUserMail = '';
      return;
    }

    const decoded = this.authService.decodeToken(token);
    this.currentUserMail = decoded?.sub ?? '';
  }

  private loadSupportMessages() {
    this.isSupportLoading = true;
    this.supportErrorMessage = '';

    this.restService.getSupportMessages().subscribe({
      next: (messages: IChatMessage[]) => {
        this.supportMessages = messages;
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
}
