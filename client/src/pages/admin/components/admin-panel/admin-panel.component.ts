import { Component, OnDestroy, OnInit } from '@angular/core';
import { ProductAdderComponent } from '../product-adder/product-adder.component';
import { CommonModule } from '@angular/common';
import { ProductBodyComponent } from '../product-body/product-body.component';
import { WallComponent } from '../wall/wall.component';
import { ProductUpdaterComponent } from '../product-adder/product-updater.component';
import { EventService } from '../../../../services/event/event.service';
import { ProductEditComponent } from '../product-edit/product-edit.component';
import { OrderPanelComponent } from '../order-panel/order-panel.component';
import { RestService } from '../../../../services/rest/rest.service';
import { FormsModule } from '@angular/forms';
import { IChatMessage } from '../../../../interfaces/IChatMessage';
import { SupportRealtimeService } from '../../../../services/support-realtime/support-realtime.service';
import { ISupportRealtimeEvent } from '../../../../interfaces/support/ISupportRealtimeEvent';
import { ISupportCustomerSummary } from '../../../../interfaces/support/ISupportCustomerSummary';
import { Observable, Subscription } from 'rxjs';
import { ActivatedRoute, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { AuthService } from '../../../../services/auth/auth.service';
import { staticImageUrl } from '../../../../app/config/supabase-assets';
import { DashboardComponent } from '../dashboard/dashboard.component';
import {
  ConfirmDialogComponent,
  ConfirmDialogData,
} from '../confirm-dialog/confirm-dialog.component';
import { ToastService } from '../../services/toast.service';

type AdminSection = 'home' | 'products' | 'orders' | 'support';

@Component({
  selector: 'app-admin-panel',
  standalone: true,
  imports: [
    ProductAdderComponent,
    ProductBodyComponent,
    CommonModule,
    WallComponent,
    ProductUpdaterComponent,
    ProductEditComponent,
    OrderPanelComponent,
    DashboardComponent,
    FormsModule,
    RouterLink,
    RouterLinkActive,
  ],
  templateUrl: './admin-panel.component.html',
  styleUrl: './admin-panel.component.css',
})
export class AdminPanelComponent implements OnInit, OnDestroy {
  readonly staticImageUrl = staticImageUrl;
  productId!: number;
  hasProductAdderOpened = false;
  hasProductsOpened = false;
  hasProductUpdaterOpened = false;
  hasProductEditOpened = false;
  hasOrdersOpened = false;
  hasSupportOpened = false;
  isLoggingOut = false;
  isSidebarOpen = false;
  currentSection: AdminSection = 'home';

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
  private routeSub: Subscription | null = null;
  private supportCustomerSearchTimer: ReturnType<typeof setTimeout> | null = null;

  private productChangedCallback!: (data: any) => void;
  private editorOpenedCallback!: (id: number) => void;

  constructor(
    private eventManager: EventService,
    private restService: RestService,
    private supportRealtimeService: SupportRealtimeService,
    private authService: AuthService,
    private route: ActivatedRoute,
    private router: Router,
    private dialog: MatDialog,
    private toastService: ToastService
  ) {
    this.productChangedCallback = (data: any) => {
      this.productChangedEventHandler(data);
    };
    this.editorOpenedCallback = () => {
      this.editorOpenedEventHandler();
    };
  }

  get sectionTitle(): string {
    switch (this.currentSection) {
      case 'products':
        return 'Ürünler';
      case 'orders':
        return 'Siparişler';
      case 'support':
        return 'Canlı Destek';
      default:
        return 'Dashboard';
    }
  }

  get sectionSubtitle(): string {
    switch (this.currentSection) {
      case 'products':
        return 'Kategori bazlı ürün yönetimi';
      case 'orders':
        return 'Sipariş durumlarını yönetin';
      case 'support':
        return 'Müşteri sohbetlerini yanıtlayın';
      default:
        return 'Mağaza genel bakışı ve son hareketler';
    }
  }

  ngOnInit(): void {
    this.eventManager.on('productChanged', this.productChangedCallback);
    this.eventManager.on('editorOpened', this.editorOpenedCallback);

    this.supportRealtimeService.connect();
    this.supportRealtimeSub = this.supportRealtimeService.events$.subscribe(
      (event: ISupportRealtimeEvent) => {
        if (!this.hasSupportOpened) {
          return;
        }

        this.loadSupportUsers();
        this.loadBannedUsers();

        if (
          this.selectedSupportUserMail &&
          this.selectedSupportUserMail === event.userMail
        ) {
          this.loadSupportMessages();
        }
      }
    );

    this.routeSub = this.route.data.subscribe((data) => {
      const section = (data['section'] ?? 'home') as AdminSection;
      this.setSection(section);
    });
  }

  ngOnDestroy(): void {
    this.eventManager.off('productChanged', this.productChangedCallback);
    this.eventManager.off('editorOpened', this.editorOpenedCallback);
    this.stopSupportPolling();
    this.supportRealtimeSub?.unsubscribe();
    this.routeSub?.unsubscribe();

    if (this.supportCustomerSearchTimer) {
      clearTimeout(this.supportCustomerSearchTimer);
      this.supportCustomerSearchTimer = null;
    }
  }

  toggleSidebar(): void {
    this.isSidebarOpen = !this.isSidebarOpen;
  }

  closeSidebar(): void {
    this.isSidebarOpen = false;
  }

  logoutAdmin() {
    if (this.isLoggingOut) {
      return;
    }

    this.isLoggingOut = true;
    this.stopSupportPolling();
    this.supportRealtimeService.disconnect();

    this.authService.logoutAdmin(() => {
      this.isLoggingOut = false;
      this.router.navigate(['/admin']);
    });
  }

  productAddedEventHandler(event: boolean) {
    if (event === true) {
      this.closeProductAdder();
      this.closeProductUpdater();
    }
  }

  editorOpenedEventHandler() {
    this.hasProductEditOpened = !this.hasProductEditOpened;
  }

  productChangedEventHandler(productId: number) {
    this.productId = productId;
    this.hasProductAdderOpened = false;
    this.hasProductUpdaterOpened = !this.hasProductUpdaterOpened;
  }

  hasWallOnClickedEventAdder(event: boolean) {
    if (event === true) {
      this.closeProductAdder();
      this.closeProductUpdater();
      this.closeProductEdit();
    }
  }

  openProductAdder() {
    this.hasProductUpdaterOpened = false;
    this.hasProductAdderOpened = true;
  }

  closeProductAdder() {
    this.hasProductAdderOpened = false;
  }

  closeProductUpdater() {
    this.hasProductUpdaterOpened = false;
  }

  closeProductEdit() {
    this.hasProductEditOpened = false;
  }

  private setSection(section: AdminSection) {
    this.currentSection = section;

    this.hasProductsOpened = section === 'products';
    this.hasOrdersOpened = section === 'orders';
    this.hasSupportOpened = section === 'support';

    if (this.hasSupportOpened) {
      this.loadSupportUsers();
      this.loadBannedUsers();
      this.startSupportPolling();
      return;
    }

    this.stopSupportPolling();
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

  loadSupportUsers() {
    this.restService.getSupportUsersForAdmin().subscribe({
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
    this.restService.getBannedSupportUsersForAdmin().subscribe({
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

    this.restService.searchSupportCustomersForAdmin(query, 20).subscribe({
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
    }).subscribe((approved) => {
      if (!approved) {
        return;
      }

      this.supportMessageActionInProgressId = message.id;
      this.restService
        .deleteSupportMessageForAdmin(this.selectedSupportUserMail, message.id)
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
    }).subscribe((approved) => {
      if (!approved) {
        return;
      }

      this.restService
        .closeSupportChatForAdmin(this.selectedSupportUserMail)
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
    }).subscribe((approved) => {
      if (!approved) {
        return;
      }

      this.restService
        .banSupportUserFromAdmin(this.selectedSupportUserMail)
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
    }).subscribe((approved) => {
      if (!approved) {
        return;
      }

      this.restService.unbanSupportUserFromAdmin(userMail).subscribe({
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

  private startSupportPolling() {
    this.stopSupportPolling();
    this.supportPollingIntervalId = setInterval(() => {
      if (this.hasSupportOpened) {
        this.loadSupportUsers();
        this.loadBannedUsers();
      }
    }, 5000);
  }

  private stopSupportPolling() {
    if (this.supportPollingIntervalId) {
      clearInterval(this.supportPollingIntervalId);
      this.supportPollingIntervalId = null;
    }
  }
}
