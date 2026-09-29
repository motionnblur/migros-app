import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatDialog } from '@angular/material/dialog';
import { Observable, Subject, Subscription, takeUntil } from 'rxjs';
import { IChatMessage } from '../../../../interfaces/IChatMessage';
import { ISupportCustomerSummary } from '../../../../interfaces/support/ISupportCustomerSummary';
import { ISupportRealtimeEvent } from '../../../../interfaces/support/ISupportRealtimeEvent';
import { RestService } from '../../../../services/rest/rest.service';
import { SupportRealtimeService } from '../../../../services/support-realtime/support-realtime.service';
import {
  ConfirmDialogComponent,
  ConfirmDialogData,
} from '../confirm-dialog/confirm-dialog.component';
import { ToastService } from '../../services/toast.service';

@Component({
  selector: 'app-admin-support',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-support.component.html',
  styleUrl: './admin-support.component.css',
})
export class AdminSupportComponent implements OnInit, OnDestroy {
  supportUsers: string[] = [];
  bannedUsers: string[] = [];
  selectedSupportUserMail = '';
  supportMessages: IChatMessage[] = [];
  supportReplyInput = '';
  editingSupportMessageId: number | null = null;
  supportEditInput = '';
  supportMessageActionInProgressId: number | null = null;
  isSupportLoading = false;
  isSupportSending = false;
  supportError = '';
  supportCustomerQuery = '';
  supportCustomerResults: ISupportCustomerSummary[] = [];
  isSupportCustomerSearchLoading = false;

  private supportPollingIntervalId: ReturnType<typeof setInterval> | null = null;
  private supportRealtimeSub: Subscription | null = null;
  private supportCustomerSearchTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly destroy$ = new Subject<void>();

  constructor(
    private restService: RestService,
    private supportRealtimeService: SupportRealtimeService,
    private dialog: MatDialog,
    private toastService: ToastService
  ) {}

  ngOnInit(): void {
    this.supportRealtimeService.connect(undefined, true);
    this.supportRealtimeSub = this.supportRealtimeService.events$.subscribe(
      (event: ISupportRealtimeEvent) => {
        this.loadSupportUsers();
        this.loadBannedUsers();

        if (this.selectedSupportUserMail === event.userMail) {
          this.loadSupportMessages();
        }
      }
    );
    this.loadSupportUsers();
    this.loadBannedUsers();
    this.startSupportPolling();
  }

  ngOnDestroy(): void {
    this.stopSupportPolling();
    this.supportRealtimeSub?.unsubscribe();
    this.supportRealtimeService.disconnect();
    this.destroy$.next();
    this.destroy$.complete();

    if (this.supportCustomerSearchTimer) {
      clearTimeout(this.supportCustomerSearchTimer);
      this.supportCustomerSearchTimer = null;
    }
  }

  loadSupportUsers() {
    this.restService.getSupportUsersForAdmin().pipe(takeUntil(this.destroy$)).subscribe({
      next: (users: string[]) => {
        this.supportUsers = users;

        if (!users.length) {
          if (!this.selectedSupportUserMail) {
            this.supportMessages = [];
          }
          return;
        }

        if (!this.selectedSupportUserMail) {
          this.selectedSupportUserMail = users[0];
        }

        if (this.selectedSupportUserMail) {
          this.loadSupportMessages();
        }
      },
      error: () => {
        this.supportError = 'Kullanıcı sohbetleri yüklenemedi.';
      },
    });
  }

  loadBannedUsers() {
    this.restService.getBannedSupportUsersForAdmin().pipe(takeUntil(this.destroy$)).subscribe({
      next: (users: string[]) => {
        this.bannedUsers = users;
      },
      error: () => {
        this.supportError = 'Banlı kullanıcılar yüklenemedi.';
      },
    });
  }

  onSupportCustomerSearchChange() {
    const query = this.supportCustomerQuery.trim();

    if (this.supportCustomerSearchTimer) {
      clearTimeout(this.supportCustomerSearchTimer);
      this.supportCustomerSearchTimer = null;
    }

    if (!query) {
      this.supportCustomerResults = [];
      this.isSupportCustomerSearchLoading = false;
      return;
    }

    this.supportCustomerSearchTimer = setTimeout(() => {
      this.searchSupportCustomers(query);
    }, 300);
  }

  private searchSupportCustomers(query: string) {
    this.isSupportCustomerSearchLoading = true;

    this.restService.searchSupportCustomersForAdmin(query, 20).pipe(takeUntil(this.destroy$)).subscribe({
      next: (customers: ISupportCustomerSummary[]) => {
        this.isSupportCustomerSearchLoading = false;
        this.supportCustomerResults = customers || [];
      },
      error: () => {
        this.isSupportCustomerSearchLoading = false;
        this.supportError = 'Kullanıcı araması yapılamadı.';
      },
    });
  }

  selectSupportCustomerFromSearch(customer: ISupportCustomerSummary) {
    if (!customer?.userMail) {
      return;
    }

    this.selectedSupportUserMail = customer.userMail;
    this.supportError = '';
    this.loadSupportMessages();
  }

  selectSupportUser(userMail: string) {
    this.selectedSupportUserMail = userMail;
    this.loadSupportMessages();
  }

  loadSupportMessages() {
    if (!this.selectedSupportUserMail) {
      this.supportMessages = [];
      return;
    }

    this.isSupportLoading = true;
    this.supportError = '';

    this.restService
      .getSupportMessagesForAdmin(this.selectedSupportUserMail)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (messages: IChatMessage[]) => {
          this.supportMessages = messages;
          this.isSupportLoading = false;
          this.editingSupportMessageId = null;
          this.supportEditInput = '';
          this.supportMessageActionInProgressId = null;
        },
        error: () => {
          this.isSupportLoading = false;
          this.supportError = 'Mesajlar yüklenemedi.';
        },
      });
  }

  startEditSupportMessage(message: IChatMessage) {
    if (this.supportMessageActionInProgressId !== null) {
      return;
    }

    this.editingSupportMessageId = message.id;
    this.supportEditInput = message.message || '';
    this.supportError = '';
  }

  cancelEditSupportMessage() {
    if (this.supportMessageActionInProgressId !== null) {
      return;
    }

    this.editingSupportMessageId = null;
    this.supportEditInput = '';
  }

  saveEditedSupportMessage(message: IChatMessage) {
    if (!this.selectedSupportUserMail || this.supportMessageActionInProgressId !== null) {
      return;
    }

    const nextMessage = this.supportEditInput.trim();
    if (!nextMessage) {
      this.supportError = 'Mesaj boş olamaz.';
      return;
    }

    this.supportMessageActionInProgressId = message.id;
    this.restService
      .editSupportMessageForAdmin(this.selectedSupportUserMail, message.id, nextMessage)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.supportMessageActionInProgressId = null;
          this.editingSupportMessageId = null;
          this.supportEditInput = '';
          this.toastService.success('Mesaj güncellendi.');
          this.loadSupportMessages();
          this.loadSupportUsers();
        },
        error: (err) => {
          this.supportMessageActionInProgressId = null;
          const messageText =
            typeof err?.error === 'string' && err.error
              ? err.error
              : 'Mesaj düzenlenemedi.';
          this.supportError = messageText;
          this.toastService.error(messageText);
        },
      });
  }

  deleteSupportMessage(message: IChatMessage) {
    if (!this.selectedSupportUserMail || this.supportMessageActionInProgressId !== null) {
      return;
    }

    this.confirmAction({
      title: 'Mesajı sil',
      message: 'Mesaj kalıcı olarak silinecek. Devam edilsin mi?',
      confirmText: 'Sil',
      danger: true,
    }).pipe(takeUntil(this.destroy$)).subscribe((approved) => {
      if (!approved) {
        return;
      }

      this.supportMessageActionInProgressId = message.id;
      this.restService
        .deleteSupportMessageForAdmin(this.selectedSupportUserMail, message.id)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: () => {
            this.supportMessageActionInProgressId = null;
            if (this.editingSupportMessageId === message.id) {
              this.editingSupportMessageId = null;
              this.supportEditInput = '';
            }
            this.toastService.success('Mesaj silindi.');
            this.loadSupportMessages();
            this.loadSupportUsers();
          },
          error: (err) => {
            this.supportMessageActionInProgressId = null;
            const messageText =
              typeof err?.error === 'string' && err.error
                ? err.error
                : 'Mesaj silinemedi.';
            this.supportError = messageText;
            this.toastService.error(messageText);
          },
        });
    });
  }

  isSupportMessageActionBusy(message: IChatMessage): boolean {
    return this.supportMessageActionInProgressId === message.id;
  }

  sendSupportReply() {
    const message = this.supportReplyInput.trim();
    if (!this.selectedSupportUserMail || !message || this.isSupportSending) {
      return;
    }

    if (this.isSelectedUserBanned()) {
      this.supportError = 'Banlı kullanıcıya mesaj gönderilemez.';
      return;
    }

    this.isSupportSending = true;
    this.restService
      .sendSupportReplyFromAdmin(this.selectedSupportUserMail, message)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.supportReplyInput = '';
          this.isSupportSending = false;
          this.loadSupportMessages();
          this.loadSupportUsers();
        },
        error: (err) => {
          this.isSupportSending = false;
          const messageText =
            typeof err?.error === 'string' && err.error
              ? err.error
              : 'Yanıt gönderilemedi.';
          this.supportError = messageText;
          this.toastService.error(messageText);
        },
      });
  }

  closeSupportChat() {
    if (!this.selectedSupportUserMail) {
      return;
    }

    this.confirmAction({
      title: 'Sohbeti kapat',
      message: `Sohbet kapatılacak: ${this.selectedSupportUserMail}`,
      confirmText: 'Kapat',
    }).pipe(takeUntil(this.destroy$)).subscribe((approved) => {
      if (!approved) {
        return;
      }

      this.restService
        .closeSupportChatForAdmin(this.selectedSupportUserMail)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: () => {
            const closedUser = this.selectedSupportUserMail;
            this.supportUsers = this.supportUsers.filter((u) => u !== closedUser);
            this.selectedSupportUserMail = this.supportUsers.length
              ? this.supportUsers[0]
              : '';
            this.supportMessages = [];
            this.supportReplyInput = '';
            this.toastService.success('Sohbet kapatıldı.');

            if (this.selectedSupportUserMail) {
              this.loadSupportMessages();
            }
          },
          error: () => {
            this.supportError = 'Sohbet kapatılamadı.';
            this.toastService.error('Sohbet kapatılamadı.');
          },
        });
    });
  }

  banSupportUser() {
    if (!this.selectedSupportUserMail) {
      return;
    }

    this.confirmAction({
      title: 'Kullanıcıyı banla',
      message: `Kullanıcı banlanacak: ${this.selectedSupportUserMail}`,
      confirmText: 'Banla',
      danger: true,
    }).pipe(takeUntil(this.destroy$)).subscribe((approved) => {
      if (!approved) {
        return;
      }

      this.restService
        .banSupportUserFromAdmin(this.selectedSupportUserMail)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: () => {
            this.supportError = 'Kullanıcı banlandı.';
            this.toastService.success('Kullanıcı banlandı.');
            this.loadBannedUsers();
          },
          error: () => {
            this.supportError = 'Kullanıcı banlanamadı.';
            this.toastService.error('Kullanıcı banlanamadı.');
          },
        });
    });
  }

  isSelectedUserBanned(): boolean {
    if (!this.selectedSupportUserMail) {
      return false;
    }

    return this.bannedUsers.includes(this.selectedSupportUserMail);
  }

  unbanSupportUser() {
    if (!this.selectedSupportUserMail) {
      return;
    }

    this.unbanUserByMail(this.selectedSupportUserMail);
  }

  unbanUserByMail(userMail: string) {
    this.confirmAction({
      title: 'Banı kaldır',
      message: `Kullanıcı banı kaldırılacak: ${userMail}`,
      confirmText: 'Banı Kaldır',
    }).pipe(takeUntil(this.destroy$)).subscribe((approved) => {
      if (!approved) {
        return;
      }

      this.restService.unbanSupportUserFromAdmin(userMail).pipe(takeUntil(this.destroy$)).subscribe({
        next: () => {
          this.supportError = 'Kullanıcı banı kaldırıldı.';
          this.toastService.success('Kullanıcı banı kaldırıldı.');
          this.loadBannedUsers();
        },
        error: () => {
          this.supportError = 'Kullanıcı ban kaldırma işlemi başarısız.';
          this.toastService.error('Kullanıcı ban kaldırma işlemi başarısız.');
        },
      });
    });
  }

  private confirmAction(data: ConfirmDialogData): Observable<boolean> {
    return this.dialog
      .open(ConfirmDialogComponent, {
        width: '420px',
        maxWidth: '92vw',
        autoFocus: false,
        panelClass: 'admin-confirm-dialog',
        data,
      })
      .afterClosed() as Observable<boolean>;
  }

  private startSupportPolling() {
    this.stopSupportPolling();
    this.supportPollingIntervalId = setInterval(() => {
      this.loadSupportUsers();
      this.loadBannedUsers();
    }, 5000);
  }

  private stopSupportPolling() {
    if (this.supportPollingIntervalId) {
      clearInterval(this.supportPollingIntervalId);
      this.supportPollingIntervalId = null;
    }
  }
}
